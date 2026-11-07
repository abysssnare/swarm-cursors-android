package com.swarmcursors.engine

import com.swarmcursors.core.geometry.PointF
import com.swarmcursors.core.geometry.RectF
import com.swarmcursors.core.log.SwarmLog
import com.swarmcursors.core.metrics.MetricsCollector
import com.swarmcursors.core.model.ActionEvent
import com.swarmcursors.core.model.ActionTicket
import com.swarmcursors.core.model.ActionType
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.CancelStage
import com.swarmcursors.core.model.FailureReason
import com.swarmcursors.core.model.SwarmConfig
import com.swarmcursors.core.motion.MotionPlanner
import com.swarmcursors.host.AccessibilityHost
import com.swarmcursors.host.ExecutionOutcome
import com.swarmcursors.host.GlobalAction
import com.swarmcursors.security.AuditLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executes queued action tickets (spec §3/§4).
 *
 * Concurrency model: a pool of worker coroutines pulls tickets from an
 * unbounded channel. Sequential mode (`parallelMode = false`) uses a single
 * worker; parallel mode uses `min(maxParallel, maxCursors)` workers, each
 * driving its own cursor.
 *
 * All mutable bookkeeping is guarded by [lock]; worker bodies run on the
 * engine scope (main dispatcher).
 */
internal class ActionEngine(
    private val scope: CoroutineScope,
    private val hostProvider: () -> AccessibilityHost,
    private val stageProvider: () -> CursorStage,
    private val configProvider: () -> SwarmConfig,
    private val metrics: MetricsCollector,
    private val audit: AuditLogger,
    private val planner: MotionPlanner = MotionPlanner(),
    private val windowIdProvider: () -> Int? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()

    private val queue = Channel<ActiveTicket>(Channel.UNLIMITED)
    private val ticketsById = LinkedHashMap<String, ActiveTicket>()
    private val workers = mutableListOf<Job>()
    private val busyCursors = mutableSetOf<Int>()

    private val resolver = TargetResolver()

    /** Enqueues a ticket and returns its dedicated event flow (spec §3.1). */
    fun inject(actions: List<AgentAction>): Flow<ActionEvent> {
        val ticket = ActionTicket(actions = actions)
        val active = ActiveTicket(ticket, Channel(Channel.UNLIMITED))
        val position: Int
        synchronized(lock) {
            ticketsById[ticket.id] = active
            position = ticketsById.size - 1
        }
        ensureWorkers()
        active.trySend(ActionEvent.TicketQueued(ticket.id, position))
        queue.trySend(active)
        SwarmLog.i("Ticket ${ticket.id} queued with ${actions.size} action(s)")
        return active.events.consumeAsFlow()
    }

    /** Cancels one ticket (spec §3.2). Returns false for unknown/finished ids. */
    fun cancelTicket(ticketId: String): Boolean {
        val active = synchronized(lock) { ticketsById[ticketId] } ?: return false
        return synchronized(lock) {
            when (active.lifecycle) {
                ActiveTicket.Lifecycle.DONE -> false // already executed: ignore

                ActiveTicket.Lifecycle.QUEUED -> {
                    active.cancelRequested = true
                    active.lifecycle = ActiveTicket.Lifecycle.DONE
                    ticketsById.remove(ticketId)
                    for (action in active.ticket.actions) {
                        active.trySend(ActionEvent.Cancelled(action, CancelStage.QUEUED))
                    }
                    active.trySend(
                        ActionEvent.TicketCompleted(
                            ticketId = ticketId,
                            succeeded = 0,
                            failed = 0,
                            cancelled = active.ticket.actions.size,
                        )
                    )
                    active.events.close()
                    SwarmLog.i("Ticket $ticketId cancelled while queued")
                    true
                }

                ActiveTicket.Lifecycle.RUNNING -> {
                    // Motion/execution loop observes the flag and reacts.
                    active.cancelRequested = true
                    SwarmLog.i("Ticket $ticketId cancel requested while running")
                    true
                }
            }
        }
    }

    /** Cancels every queued and running ticket (spec §3.1). */
    fun cancelAll() {
        val ids = synchronized(lock) { ticketsById.keys.toList() }
        for (id in ids) cancelTicket(id)
    }

    /** Queued + running tickets in submission order (spec §3.1). */
    fun pendingTickets(): List<ActionTicket> = synchronized(lock) {
        ticketsById.values.map { it.ticket }
    }

    /** Called when config changes may require more workers (spec §7.1). */
    fun onConcurrencyMaybeChanged() {
        ensureWorkers()
    }

    private fun ensureWorkers() {
        val desired = configProvider().effectiveParallelism
        while (workers.size < desired) {
            val index = workers.size
            val job = scope.launch { workerLoop(index) }
            synchronized(lock) { workers.add(job) }
        }
    }

    private suspend fun workerLoop(index: Int) {
        while (scope.isActive) {
            if (index >= configProvider().effectiveParallelism) {
                SwarmLog.d("Worker $index exiting (parallelism reduced)")
                return
            }
            val active = queue.receive()
            val shouldRun = synchronized(lock) {
                when (active.lifecycle) {
                    ActiveTicket.Lifecycle.DONE -> false
                    ActiveTicket.Lifecycle.QUEUED -> {
                        active.lifecycle = ActiveTicket.Lifecycle.RUNNING
                        active.cursorId = acquireCursorLocked()
                        true
                    }
                    ActiveTicket.Lifecycle.RUNNING -> false
                }
            }
            if (!shouldRun) continue
            try {
                processTicket(active)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                SwarmLog.e("Ticket ${active.ticket.id} crashed", t)
                active.trySend(
                    ActionEvent.Failed(
                        action = null,
                        reason = FailureReason.EXECUTION_REJECTED,
                        message = "internal error: ${t.message}",
                    )
                )
                active.trySend(
                    ActionEvent.TicketCompleted(active.ticket.id, 0, 1, 0)
                )
                active.events.close()
            } finally {
                synchronized(lock) {
                    busyCursors.remove(active.cursorId)
                    active.lifecycle = ActiveTicket.Lifecycle.DONE
                    ticketsById.remove(active.ticket.id)
                }
            }
        }
    }

    private fun acquireCursorLocked(): Int {
        val maxCursors = configProvider().maxCursors
        for (id in 0 until maxCursors) {
            if (id !in busyCursors) {
                busyCursors.add(id)
                return id
            }
        }
        return 0
    }

    private suspend fun processTicket(active: ActiveTicket) {
        val stage = stageProvider()
        stage.ensureReady()
        var succeeded = 0
        var failed = 0
        var cancelled = 0
        try {
            for (action in active.ticket.actions) {
                if (active.cancelRequested) {
                    active.trySend(ActionEvent.Cancelled(action, CancelStage.QUEUED))
                    cancelled++
                    continue
                }
                when (executeAction(active, action, stage)) {
                    Outcome.OK -> succeeded++
                    Outcome.FAILED -> failed++
                    Outcome.CANCELLED -> cancelled++
                }
            }
        } finally {
            active.trySend(
                ActionEvent.TicketCompleted(active.ticket.id, succeeded, failed, cancelled)
            )
            active.events.close()
            stage.onCursorHidden(active.cursorId)
        }
    }

    private enum class Outcome { OK, FAILED, CANCELLED }

    private suspend fun executeAction(
        active: ActiveTicket,
        action: AgentAction,
        stage: CursorStage,
    ): Outcome {
        val config = configProvider()
        val host = hostProvider()
        active.trySend(ActionEvent.ActionStarted(action))

        if (!host.isReady()) {
            return fail(active, action, FailureReason.SERVICE_NOT_READY, "service not connected", 1)
        }

        // Global actions need no target and no motion (BACK/HOME).
        if (action.type == ActionType.BACK || action.type == ActionType.HOME) {
            return executeGlobalAction(active, action, host, config)
        }

        var attempt = 0
        while (true) {
            attempt++
            val resolved = resolver.resolve(host, action, windowIdProvider())

            if (resolved is ResolvedTarget.NotFound) {
                if (config.retryPolicy.shouldRetry(resolved.reason, attempt)) {
                    SwarmLog.w("Resolve attempt $attempt failed (${resolved.reason}); retrying")
                    delay(config.retryPolicy.backoffMs)
                    continue
                }
                return fail(active, action, resolved.reason, resolved.message, attempt)
            }

            // --- Motion phase ---
            val point: PointF
            val widthPx: Float
            val bounds: RectF
            when (resolved) {
                is ResolvedTarget.NodeTarget -> {
                    point = resolved.point
                    widthPx = resolved.widthPx
                    bounds = resolved.bounds
                }
                is ResolvedTarget.PointTarget -> {
                    point = resolved.point
                    widthPx = resolved.widthPx
                    bounds = resolved.bounds
                }
                is ResolvedTarget.NotFound -> continue // unreachable
            }

            stage.onTargetBounds(active.cursorId, bounds)
            val motion = runMotion(active, action, stage, point, widthPx, config)

            when (motion) {
                MotionOutcome.CANCELLED -> {
                    stage.onCursorCancelled(active.cursorId)
                    active.trySend(ActionEvent.Cancelled(action, CancelStage.MOVING))
                    return Outcome.CANCELLED
                }
                MotionOutcome.TIMEOUT -> {
                    if (config.retryPolicy.shouldRetry(FailureReason.MOTION_TIMEOUT, attempt)) {
                        delay(config.retryPolicy.backoffMs)
                        continue
                    }
                    return fail(active, action, FailureReason.MOTION_TIMEOUT, null, attempt)
                }
                MotionOutcome.COMPLETED -> Unit
            }

            // Cancel right before dispatching the real action (spec §3.2).
            if (active.cancelRequested) {
                stage.onCursorCancelled(active.cursorId)
                active.trySend(ActionEvent.Cancelled(action, CancelStage.EXECUTING))
                return Outcome.CANCELLED
            }

            // --- Execution phase ---
            active.trySend(ActionEvent.Executing(action))
            stage.onCursorExecuting(active.cursorId)

            val outcome: ExecutionOutcome? = withTimeoutOrNull(config.actionTimeoutMs) {
                if (resolved is ResolvedTarget.NodeTarget) {
                    performOnNode(host, resolved, action)
                } else {
                    performOnPoint(host, action, point)
                }
            }

            if (outcome == null) {
                if (config.retryPolicy.shouldRetry(FailureReason.EXECUTION_TIMEOUT, attempt)) {
                    delay(config.retryPolicy.backoffMs)
                    continue
                }
                return fail(active, action, FailureReason.EXECUTION_TIMEOUT, null, attempt)
            }

            if (lastMotionFrameCount > 0) {
                metrics.recordMotion(lastMotionFrameCount * MotionPlanner.FRAME_MS)
            }

            if (outcome is ExecutionOutcome.Stale) {
                if (config.retryPolicy.shouldRetry(FailureReason.NODE_STALE, attempt)) {
                    SwarmLog.w("Node stale on attempt $attempt — re-resolving and retrying")
                    delay(config.retryPolicy.backoffMs)
                    continue
                }
                return fail(active, action, FailureReason.NODE_STALE, null, attempt)
            }

            when (outcome) {
                is ExecutionOutcome.Completed -> {
                    metrics.recordExecution(outcome.durationMs)
                    if (outcome.success) {
                        metrics.recordActionResult(true)
                        stage.onCursorActionCompleted(active.cursorId)
                        active.trySend(ActionEvent.Executed(action, true, outcome.durationMs, attempt))
                        return Outcome.OK
                    } else {
                        // performAction returned false (spec §4.1/§4.2).
                        if (config.retryPolicy.shouldRetry(FailureReason.EXECUTION_REJECTED, attempt)) {
                            SwarmLog.w("Execution rejected on attempt $attempt — retrying")
                            delay(config.retryPolicy.backoffMs)
                            continue
                        }
                        return fail(active, action, FailureReason.EXECUTION_REJECTED, null, attempt)
                    }
                }

                ExecutionOutcome.Stale -> return fail(active, action, FailureReason.NODE_STALE, null, attempt)

                ExecutionOutcome.NotReady -> {
                    return fail(active, action, FailureReason.SERVICE_NOT_READY, null, attempt)
                }
            }
        }
    }

    private suspend fun executeGlobalAction(
        active: ActiveTicket,
        action: AgentAction,
        host: AccessibilityHost,
        config: SwarmConfig,
    ): Outcome {
        val stage = stageProvider()
        active.trySend(ActionEvent.Executing(action))
        stage.onCursorExecuting(active.cursorId)
        val started = clock()
        val outcome = withTimeoutOrNull(config.actionTimeoutMs) {
            val global = if (action.type == ActionType.BACK) GlobalAction.BACK else GlobalAction.HOME
            ExecutionOutcome.Completed(host.performGlobalAction(global), clock() - started)
        }
        if (outcome != null) {
            audit.record(action, outcome.success, outcome.durationMs, clock())
        }
        return if (outcome == null) {
            fail(active, action, FailureReason.EXECUTION_TIMEOUT, null, 1)
        } else {
            metrics.recordExecution(outcome.durationMs)
            metrics.recordActionResult(outcome.success)
            if (outcome.success) {
                stage.onCursorActionCompleted(active.cursorId)
                active.trySend(ActionEvent.Executed(action, true, outcome.durationMs, 1))
                Outcome.OK
            } else {
                active.trySend(ActionEvent.Failed(action, FailureReason.EXECUTION_REJECTED, null, 1))
                Outcome.FAILED
            }
        }
    }

    /** Node-scoped dispatch with the §4.3 freshness guard. Audits each
     *  real attempt individually. */
    private fun performOnNode(
        host: AccessibilityHost,
        resolved: ResolvedTarget.NodeTarget,
        action: AgentAction,
    ): ExecutionOutcome {
        val handle = resolved.handle
        val fresh = runCatching { handle.refresh() }.getOrDefault(false)
        if (!fresh ||
            !runCatching { handle.isVisibleToUser() }.getOrDefault(false) ||
            !runCatching { handle.isEnabled() }.getOrDefault(false)
        ) {
            return ExecutionOutcome.Stale
        }
        val started = clock()
        val accepted = runCatching { handle.performAction(action) }.getOrDefault(false)
        if (accepted) {
            val duration = clock() - started
            audit.record(action, result = true, durationMs = duration, timestampMs = clock())
            return ExecutionOutcome.Completed(true, duration)
        }
        // Gesture fallback for click-like actions whose node rejects the call.
        val fallbackOk = (action.type == ActionType.CLICK || action.type == ActionType.LONG_CLICK) &&
            host.injectTap(resolved.point.x, resolved.point.y)
        val duration = clock() - started
        audit.record(action, result = fallbackOk, durationMs = duration, timestampMs = clock())
        return ExecutionOutcome.Completed(fallbackOk, duration)
    }

    /** Coordinate-only dispatch (gesture tap / global action). */
    private fun performOnPoint(
        host: AccessibilityHost,
        action: AgentAction,
        point: PointF,
    ): ExecutionOutcome {
        val started = clock()
        val ok = when (action.type) {
            ActionType.BACK -> host.performGlobalAction(GlobalAction.BACK)
            ActionType.HOME -> host.performGlobalAction(GlobalAction.HOME)
            else -> host.injectTap(point.x, point.y)
        }
        val duration = clock() - started
        audit.record(action, result = ok, durationMs = duration, timestampMs = clock())
        return ExecutionOutcome.Completed(ok, duration)
    }

    private var lastMotionFrameCount: Int = 0

    /**
     * Animates the cursor to the target point. Frame-based so unit tests can
     * drive it with a virtual-time scheduler (spec §3.3 timeout via
     * [withTimeoutOrNull]). Returns the number of rendered frames.
     */
    private suspend fun runMotion(
        active: ActiveTicket,
        action: AgentAction,
        stage: CursorStage,
        target: PointF,
        targetWidthPx: Float,
        config: SwarmConfig,
    ): MotionOutcome {
        val cursorId = active.cursorId
        val label = if (config.enableActionLabel) action.type.name else null
        stage.onCursorShown(cursorId, label)

        var assumedScreen = stage.screenSize()
        var current = stage.currentPosition(cursorId) ?: spawnPoint(cursorId, assumedScreen)
        var path = planner.plan(
            from = current,
            to = target,
            style = config.motionStyle,
            targetWidthPx = targetWidthPx,
            durationHintMs = action.durationHintMs,
            seed = action.id.hashCode().toLong(),
        )
        var totalFrames = path.totalFrames
        var framesDone = 0

        val outcome = withTimeoutOrNull(config.actionTimeoutMs) {
            while (framesDone < totalFrames) {
                if (active.cancelRequested) return@withTimeoutOrNull MotionOutcome.CANCELLED
                framesDone++
                val progress = framesDone.toFloat() / totalFrames
                val position = path.positionAt(progress)
                stage.onCursorMoved(cursorId, position.x, position.y)
                active.trySend(ActionEvent.Moving(action, progress, position.x, position.y))

                // Spec §6.3: remap when the display geometry changed mid-flight.
                if (framesDone % 15 == 0) {
                    val sizeNow = stage.screenSize()
                    if (sizeNow != assumedScreen) {
                        val oldScreen = RectF(0f, 0f, assumedScreen.x, assumedScreen.y)
                        val newScreen = RectF(0f, 0f, sizeNow.x, sizeNow.y)
                        val remapped = path.to.remap(oldScreen, newScreen)
                        current = position
                        path = planner.plan(
                            from = current,
                            to = remapped,
                            style = config.motionStyle,
                            targetWidthPx = targetWidthPx,
                            durationHintMs = action.durationHintMs,
                            seed = action.id.hashCode().toLong(),
                        )
                        totalFrames = path.totalFrames
                        framesDone = 0
                        assumedScreen = sizeNow
                        SwarmLog.i("Display geometry changed — motion remapped")
                    }
                }

                delay(MotionPlanner.FRAME_MS)
            }
            MotionOutcome.COMPLETED
        }
        lastMotionFrameCount = framesDone
        return outcome ?: MotionOutcome.TIMEOUT
    }

    private fun spawnPoint(cursorId: Int, screen: PointF): PointF =
        PointF(
            x = (screen.x * (0.22f + 0.07f * cursorId)).coerceAtMost(screen.x * 0.9f),
            y = screen.y * 0.3f,
        )

    private fun fail(
        active: ActiveTicket,
        action: AgentAction,
        reason: FailureReason,
        message: String?,
        attempts: Int,
    ): Outcome {
        metrics.recordActionResult(false)
        active.trySend(ActionEvent.Failed(action, reason, message, attempts))
        SwarmLog.w("Action ${action.id} failed: $reason (attempts=$attempts)")
        return Outcome.FAILED
    }

    private enum class MotionOutcome {
        COMPLETED,
        CANCELLED,
        TIMEOUT,
    }
}

/**
 * Internal per-ticket runtime record.
 */
internal class ActiveTicket(
    val ticket: ActionTicket,
    val events: Channel<ActionEvent>,
) {
    enum class Lifecycle { QUEUED, RUNNING, DONE }

    @Volatile
    var cancelRequested: Boolean = false

    var lifecycle: Lifecycle = Lifecycle.QUEUED

    var cursorId: Int = 0

    fun trySend(event: ActionEvent) {
        events.trySend(event)
    }
}
