package com.swarmcursors

import android.app.ApplicationInfo
import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.swarmcursors.core.log.SwarmLog
import com.swarmcursors.core.metrics.MetricsCollector
import com.swarmcursors.core.model.ActionEvent
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.ActionTicket
import com.swarmcursors.core.model.AuditEntry
import com.swarmcursors.core.model.InterfaceSnapshot
import com.swarmcursors.core.model.FailureReason
import com.swarmcursors.core.model.NodeSummary
import com.swarmcursors.core.model.ServiceState
import com.swarmcursors.core.model.SnapshotConfig
import com.swarmcursors.core.model.SwarmConfig
import com.swarmcursors.core.model.SwarmMetrics
import com.swarmcursors.core.model.WindowTarget
import com.swarmcursors.core.motion.MotionPlanner
import com.swarmcursors.engine.ActionEngine
import com.swarmcursors.engine.CursorStage
import com.swarmcursors.engine.MockCursorStage
import com.swarmcursors.host.AccessibilityHost
import com.swarmcursors.host.RealAccessibilityHost
import com.swarmcursors.notification.KeepAliveNotifications
import com.swarmcursors.overlay.OverlayController
import com.swarmcursors.security.AuditLogger
import com.swarmcursors.service.AccessibilitySettingsHelper
import com.swarmcursors.service.ServiceStateMonitor
import com.swarmcursors.service.SwarmAccessibilityService
import com.swarmcursors.snapshot.SnapshotEngine
import com.swarmcursors.test.MockAccessibilityHost
import com.swarmcursors.windows.WindowRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Public entry point of the Swarm Cursors library.
 *
 * Quick start:
 * ```kotlin
 * if (!SwarmCursors.isAccessibilityServiceEnabled(context)) {
 *     SwarmCursors.openAccessibilitySettings(context)
 * }
 * SwarmCursors.observeServiceState(context).collect { state ->
 *     if (state is ServiceState.Ready) {
 *         SwarmCursors.injectActions(listOf(
 *             AgentAction(type = ActionType.CLICK, target = ActionTarget.node("r/0/3"))
 *         )).collect { event -> /* Moving / Executed / Failed ... */ }
 *     }
 * }
 * ```
 */
object SwarmCursors {

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    /** Current configuration (spec §7.1). */
    private val configRef = AtomicReference(SwarmConfig())

    internal val metricsCollector = MetricsCollector()
    private val auditLogger = AuditLogger()
    private val planner = MotionPlanner()
    private val windowRegistry = WindowRegistry()

    private val realHost = RealAccessibilityHost()

    // Test seam (spec §9.1): when set, the mock host + stage are used.
    @Volatile
    internal var hostOverride: AccessibilityHost? = null

    @Volatile
    internal var stageOverride: CursorStage? = null

    @Volatile
    private var engine: ActionEngine? = null

    @Volatile
    private var snapshotEngine: SnapshotEngine? = null

    @Volatile
    private var overlayController: OverlayController? = null

    @Volatile
    private var debugOverlayDesired = false

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var engineScope: CoroutineScope? = null

    /**
     * Engine scope resolved lazily so tests can swap the Main dispatcher
     * between runs (shutdown drops the scope).
     */
    private fun engineScope(): CoroutineScope {
        val existing = engineScope
        if (existing != null && existing.isActive) return existing
        synchronized(lock) {
            val current = engineScope
            if (current != null && current.isActive) return current
            val created = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            engineScope = created
            return created
        }
    }

    // ------------------------------------------------------------------
    // §1 Permission guidance & state management
    // ------------------------------------------------------------------

    /** True when the accessibility service is enabled in system settings. */
    @JvmStatic
    fun isAccessibilityServiceEnabled(context: Context): Boolean =
        AccessibilitySettingsHelper.isServiceEnabled(context) ||
            SwarmAccessibilityService.getInstance() != null

    /** True when the cursor overlay window is currently attached. */
    @JvmStatic
    fun isOverlayReady(): Boolean = overlayController?.isAttached() == true

    /** Current service state (spec §1.1). */
    @JvmStatic
    fun getServiceState(): ServiceState {
        if (hostOverride != null) return ServiceState.Ready
        if (SwarmAccessibilityService.getInstance() != null) return ServiceState.Ready
        return ServiceStateMonitor.current()
    }

    /** Opens the system accessibility settings page (spec §1.2). */
    @JvmStatic
    fun openAccessibilitySettings(context: Context) {
        AccessibilitySettingsHelper.openAccessibilitySettings(context)
    }

    /**
     * Observes service state changes (spec §1.3). The underlying
     * `StateFlow` replays the current state to new collectors and reacts to
     * settings changes and service (dis)connects within ~1s.
     */
    @JvmStatic
    fun observeServiceState(context: Context): Flow<ServiceState> {
        ensureInitialized(context)
        ServiceStateMonitor.start(context)
        return ServiceStateMonitor.stateFlow
    }

    // ------------------------------------------------------------------
    // §2 Service lifecycle helpers
    // ------------------------------------------------------------------

    /**
     * Ready-to-use keep-alive notification (spec §2.3). The library never
     * calls `startForeground` itself; integrators that want a foreground
     * service use this.
     */
    @JvmStatic
    fun createKeepAliveNotification(context: Context): Notification =
        KeepAliveNotifications.create(context)

    /**
     * Shows/hides the cursor overlay explicitly (spec §2.2). Attaching on
     * first `injectActions` happens automatically; this is for integrators
     * who want a visible idle cursor.
     */
    @JvmStatic
    fun setCursorVisible(visible: Boolean): Boolean {
        val controller = ensureOverlayController()
        if (controller == null) {
            SwarmLog.w("setCursorVisible: library not initialized with a Context yet")
            return false
        }
        if (visible) {
            // attach() posts itself when off-main; treat as accepted.
            mainHandler.post { controller.attach() }
            return true
        }
        mainHandler.post { controller.detach() }
        return true
    }

    // ------------------------------------------------------------------
    // §3 Action queue & cancellation
    // ------------------------------------------------------------------

    /**
     * Injects a ticket of actions and returns its independent event flow
     * (spec §3.1). Tickets are executed in submission order under the
     * configured concurrency policy.
     *
     * When the service is not [ServiceState.Ready], the returned flow emits
     * a single `Failed(SERVICE_NOT_READY)` immediately — it never throws.
     */
    @JvmStatic
    fun injectActions(actions: List<AgentAction>): Flow<ActionEvent> {
        val state = getServiceState()
        if (state !is ServiceState.Ready) {
            SwarmLog.w("injectActions rejected: service state is $state")
            return flow {
                emit(
                    ActionEvent.Failed(
                        action = null,
                        reason = FailureReason.SERVICE_NOT_READY,
                        message = "Service state: $state",
                    )
                )
            }
        }
        return engine().inject(actions)
    }

    /**
     * Cancels a ticket (spec §3.2). Returns true when the ticket existed and
     * had not finished; cancelling already-executed actions is a no-op.
     */
    @JvmStatic
    fun cancelTicket(ticketId: String): Boolean = engine().cancelTicket(ticketId)

    /** Cancels all queued and running tickets (spec §3.1). */
    @JvmStatic
    fun cancelAll() = engine().cancelAll()

    /** Queued and currently running tickets, in submission order. */
    @JvmStatic
    fun getPendingTickets(): List<ActionTicket> = engine().pendingTickets()

    // ------------------------------------------------------------------
    // §5 Snapshots
    // ------------------------------------------------------------------

    /**
     * Captures an interface snapshot (spec §5). Suspending; runs on the main
     * dispatcher because accessibility trees must be read there. Returns
     * null when the service is not ready or no root is available.
     *
     * Passing `null` uses [SnapshotConfig] defaults with the settle delay
     * taken from [SwarmConfig.snapshotDelayMs].
     */
    suspend fun captureSnapshot(config: SnapshotConfig? = null): InterfaceSnapshot? {
        val effective = config ?: SnapshotConfig(settleDelayMs = getConfig().snapshotDelayMs)
        return snapshotEngine().capture(effective)
    }

    // ------------------------------------------------------------------
    // §6 Multi-window
    // ------------------------------------------------------------------

    /** Non-PiP windows (spec §6.1/§6.2). Empty when the service is not ready. */
    @JvmStatic
    fun listWindows(): List<WindowTarget> = currentHost().windows()

    /** Targets a specific window for subsequent actions (spec §6.1). */
    @JvmStatic
    fun setTargetWindow(windowId: Int) {
        windowRegistry.setTarget(windowId)
    }

    /** Falls back to the active window for subsequent actions. */
    @JvmStatic
    fun clearTargetWindow() {
        windowRegistry.setTarget(null)
    }

    // ------------------------------------------------------------------
    // §7 Configuration & themes
    // ------------------------------------------------------------------

    /** Applies a new configuration at runtime (spec §7.1). */
    @JvmStatic
    fun updateConfig(config: SwarmConfig) {
        val previous = configRef.getAndSet(config)
        engine?.onConcurrencyMaybeChanged()
        val coldFieldsChanged = previous.parallelMode != config.parallelMode ||
            previous.maxParallel != config.maxParallel
        if (coldFieldsChanged) {
            val controller = overlayController
            if (controller != null && controller.isAttached()) {
                SwarmLog.i("parallelMode/maxParallel changed — rebuilding overlay")
                mainHandler.post { controller.rebuild() }
            }
        }
        SwarmLog.i("Config updated: theme=${config.theme}, maxCursors=${config.maxCursors}")
    }

    /** Current configuration. */
    @JvmStatic
    fun getConfig(): SwarmConfig = configRef.get()

    internal fun configProvider(): SwarmConfig = configRef.get()

    // ------------------------------------------------------------------
    // §8 Observability
    // ------------------------------------------------------------------

    /** Aggregated metrics (spec §8.2). */
    @JvmStatic
    fun getMetrics(): SwarmMetrics = metricsCollector.snapshot()

    /** Resets all metrics (spec §8.2). */
    @JvmStatic
    fun resetMetrics() = metricsCollector.reset()

    /**
     * Enables the debug overlay: cursor ids, target bounds (red dashed),
     * FPS and current phase (spec §8.3). Only works in debuggable builds.
     */
    @JvmStatic
    fun setDebugOverlayEnabled(enabled: Boolean) {
        val context = appContext
        if (context != null) {
            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (!debuggable) {
                SwarmLog.w("setDebugOverlayEnabled ignored — only available in debuggable builds")
                return
            }
        }
        debugOverlayDesired = enabled
        overlayController?.setDebugEnabled(enabled)
    }

    // ------------------------------------------------------------------
    // §10 Security
    // ------------------------------------------------------------------

    /**
     * Installs an audit listener (spec §10.2). Entries never contain typed
     * text. Pass null to remove the listener.
     */
    @JvmStatic
    fun setAuditListener(listener: ((AuditEntry) -> Unit)?) {
        auditLogger.setListener(listener)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Stops workers, detaches the overlay and releases engine resources. */
    @JvmStatic
    fun shutdown() {
        val controller = overlayController
        synchronized(lock) {
            engineScope?.cancel()
            engineScope = null
            engine = null
            snapshotEngine = null
            overlayController = null
        }
        if (controller != null) {
            // Spec §2.2: remove the overlay window and free its surface.
            mainHandler.post { controller.detach() }
        }
        SwarmLog.i("SwarmCursors shut down")
    }

    // ------------------------------------------------------------------
    // Internal wiring
    // ------------------------------------------------------------------

    private fun ensureInitialized(context: Context) {
        if (appContext != null) return
        synchronized(lock) {
            if (appContext == null) {
                appContext = context.applicationContext
                ServiceStateMonitor.start(appContext!!)
                // §8.3: re-validate the debug overlay desire now that the
                // application info is available.
                if (debugOverlayDesired) {
                    val debuggable =
                        (appContext!!.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    if (!debuggable) {
                        debugOverlayDesired = false
                        SwarmLog.w("setDebugOverlayEnabled ignored — release build")
                    }
                }
            }
        }
    }

    private fun currentHost(): AccessibilityHost = hostOverride ?: realHost

    private fun currentStage(): CursorStage = stageOverride ?: overlayStage()

    private fun overlayStage(): CursorStage {
        val controller = ensureOverlayController()
        return controller ?: NoopCursorStage
    }

    private fun ensureOverlayController(): OverlayController? {
        val existing = overlayController
        if (existing != null) return existing
        val context = appContext ?: return null
        val controller = OverlayController(context, ::configProvider, metricsCollector)
        overlayController = controller
        return controller
    }

    private fun engine(): ActionEngine {
        val existing = engine
        if (existing != null) return existing
        synchronized(lock) {
            val current = engine
            if (current != null) return current
            val scope = engineScope()
            val created = ActionEngine(
                scope = scope,
                hostProvider = { currentHost() },
                stageProvider = { currentStage() },
                configProvider = ::configProvider,
                metrics = metricsCollector,
                audit = auditLogger,
                planner = planner,
                windowIdProvider = { windowRegistry.targetWindowId },
            )
            engine = created
            return created
        }
    }

    private fun snapshotEngine(): SnapshotEngine {
        val existing = snapshotEngine
        if (existing != null) return existing
        synchronized(lock) {
            val current = snapshotEngine
            if (current != null) return current
            val created = SnapshotEngine(
                hostProvider = { currentHost() },
                windowIdProvider = { windowRegistry.targetWindowId },
                metrics = metricsCollector,
            )
            snapshotEngine = created
            return created
        }
    }

    // Internal service lifecycle hooks (called by SwarmAccessibilityService).

    internal fun onServiceConnectedInternal() {
        ServiceStateMonitor.onServiceConnected()
    }

    internal fun onServiceUnboundInternal() {
        ServiceStateMonitor.onServiceDisconnected()
        val controller = overlayController ?: return
        // Spec §2.2: remove overlay, stop rendering, free the surface.
        mainHandler.post { controller.detach() }
    }

    internal fun onDisplayGeometryChangedInternal() {
        val controller = overlayController ?: return
        mainHandler.post { controller.onDisplayGeometryChanged() }
    }

    internal fun onOverlayPauseInternal() {
        overlayController?.pauseRendering()
    }

    internal fun onOverlayResumeInternal() {
        overlayController?.resumeRendering()
    }

    // Test support hooks (spec §9.1).

    internal fun installMockHost(
        nodes: List<NodeSummary>,
        actionResults: Map<com.swarmcursors.core.model.ActionType, Boolean>,
    ) {
        val mock = MockAccessibilityHost(nodes)
        mock.actionResults = actionResults
        hostOverride = mock
        stageOverride = MockCursorStage()
        ServiceStateMonitor.setTestOverride(ServiceState.Ready)
        SwarmLog.i("Mock accessibility host installed (${nodes.size} nodes)")
    }

    internal fun clearMockHost() {
        hostOverride = null
        stageOverride = null
        ServiceStateMonitor.setTestOverride(null)
        SwarmLog.i("Mock accessibility host removed")
    }

    internal fun mockHost(): MockAccessibilityHost? = hostOverride as? MockAccessibilityHost

    /** Stage used before initialization: drops all events silently. */
    private object NoopCursorStage : CursorStage {
        override fun ensureReady(): Boolean = false
        override fun onCursorShown(cursorId: Int, label: String?) = Unit
        override fun onCursorMoved(cursorId: Int, x: Float, y: Float) = Unit
        override fun onCursorExecuting(cursorId: Int) = Unit
        override fun onCursorActionCompleted(cursorId: Int) = Unit
        override fun onCursorCancelled(cursorId: Int) = Unit
        override fun onCursorHidden(cursorId: Int) = Unit
        override fun currentPosition(cursorId: Int) = null
        override fun screenSize() = com.swarmcursors.core.geometry.PointF(1080f, 2400f)
        override fun onTargetBounds(cursorId: Int, bounds: com.swarmcursors.core.geometry.RectF?) = Unit
        override fun isAttached(): Boolean = false
    }
}
