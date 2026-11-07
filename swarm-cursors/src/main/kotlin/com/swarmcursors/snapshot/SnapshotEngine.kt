package com.swarmcursors.snapshot

import com.swarmcursors.core.log.SwarmLog
import com.swarmcursors.core.metrics.MetricsCollector
import com.swarmcursors.core.model.InterfaceSnapshot
import com.swarmcursors.core.model.NodeSummary
import com.swarmcursors.core.model.SnapshotConfig
import com.swarmcursors.core.snapshot.SnapshotDiffEngine
import com.swarmcursors.core.snapshot.SnapshotSanitizer
import com.swarmcursors.core.snapshot.SnapshotStability
import com.swarmcursors.host.AccessibilityHost
import com.swarmcursors.host.NodeHandle
import com.swarmcursors.host.NodePathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Captures interface snapshots (spec §5).
 *
 * Pipeline per `capture` call:
 *  1. settle delay (`SnapshotConfig.settleDelayMs`, default 200 ms),
 *  2. two probes `stabilityIntervalMs` apart (default 80 ms); bounds deltas
 *     below `stabilityThresholdPx` (default 5 px) count as stable,
 *  3. up to `maxStabilityRetries` re-checks; the last result is returned with
 *     `isStable = false` when stability was never reached,
 *  4. optional diff against the previous snapshot by node id,
 *  5. sensitive text redaction (spec §10.1) and `maxNodes` truncation by
 *     visibility-then-area priority.
 */
internal class SnapshotEngine(
    private val hostProvider: () -> AccessibilityHost,
    private val windowIdProvider: () -> Int?,
    private val metrics: MetricsCollector,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    @Volatile
    private var lastSnapshot: InterfaceSnapshot? = null

    /** Drops the diff base so the next capture has `diff = null`. */
    fun resetDiffBase() {
        lastSnapshot = null
    }

    suspend fun capture(config: SnapshotConfig): InterfaceSnapshot? =
        withContext(Dispatchers.Main) {
            val host = hostProvider()
            if (!host.isReady()) {
                SwarmLog.w("captureSnapshot: accessibility service not ready")
                return@withContext null
            }
            val windowId = windowIdProvider()
            val startedAt = clock()

            if (config.settleDelayMs > 0) delay(config.settleDelayMs)

            var stable = false
            var attempts = 0
            var result: InterfaceSnapshot? = null
            while (attempts <= config.maxStabilityRetries) {
                attempts++
                val first = captureOnce(host, config, windowId)
                if (first == null) {
                    SwarmLog.w("captureSnapshot: no root node available")
                    return@withContext null
                }
                delay(config.stabilityIntervalMs)
                val second = captureOnce(host, config, windowId)
                if (second == null) {
                    SwarmLog.w("captureSnapshot: no root node available on second probe")
                    return@withContext null
                }
                stable = SnapshotStability.isStable(first.nodes, second.nodes, config.stabilityThresholdPx)
                result = second
                if (stable) break
                SwarmLog.d("Snapshot unstable (attempt $attempts) — retrying")
            }

            val captured = result ?: return@withContext null
            val diff = if (config.computeDiff) {
                lastSnapshot?.let { SnapshotDiffEngine.compute(it.nodes, captured.nodes) }
            } else {
                null
            }
            val final = captured.copy(diff = diff, isStable = stable)
            lastSnapshot = final
            metrics.recordSnapshot(clock() - startedAt)
            SwarmLog.i(
                "Snapshot captured: ${final.nodes.size} nodes, stable=$stable, " +
                    "diff(+${diff?.added?.size ?: 0} -${diff?.removed?.size ?: 0} ~${diff?.changed?.size ?: 0})"
            )
            final
        }

    /** Single pass over the node tree. */
    private fun captureOnce(
        host: AccessibilityHost,
        config: SnapshotConfig,
        windowId: Int?,
    ): InterfaceSnapshot? {
        val root = host.rootInWindow(windowId) ?: return null

        val hardCap = maxOf(config.maxNodes * 4, 2_000)
        val collected = ArrayList<NodeSummary>(minOf(config.maxNodes, 256))

        data class Frame(
            val handle: NodeHandle,
            val id: String,
            val parentId: String?,
            val depth: Int,
        )

        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, NodePathResolver.ROOT_ID, null, 0))
        var visited = 0
        while (stack.isNotEmpty() && visited < hardCap) {
            val frame = stack.removeLast()
            visited++
            val summary = runCatching {
                frame.handle.toSummary(frame.id, frame.parentId, frame.depth)
            }.getOrNull() ?: continue
            val sanitized = SnapshotSanitizer.sanitizeNode(summary, config.includeSensitiveText)
            if (config.includeInvisible || sanitized.isVisibleToUser || frame.depth == 0) {
                collected.add(sanitized)
            }
            val childCount = runCatching { frame.handle.childCount() }.getOrDefault(0)
            if (childCount > 0) {
                // Push in reverse so the traversal order matches child order.
                for (index in (childCount - 1) downTo 0) {
                    val child = runCatching { frame.handle.child(index) }.getOrNull() ?: continue
                    stack.addLast(
                        Frame(child, NodePathResolver.childId(frame.id, index), frame.id, frame.depth + 1)
                    )
                }
            }
        }

        val nodes = truncate(collected, config.maxNodes)

        val windows = host.windows()
        val window = if (windowId != null) {
            windows.firstOrNull { it.windowId == windowId }
        } else {
            windows.firstOrNull { it.isActive }
        }

        return InterfaceSnapshot(
            nodes = nodes,
            timestampMs = clock(),
            windowTitle = window?.title,
            windowId = window?.windowId ?: -1,
        )
    }

    /** Spec §5.3: truncation by visibility first, then area. */
    private fun truncate(nodes: List<NodeSummary>, maxNodes: Int): List<NodeSummary> {
        if (nodes.size <= maxNodes) return nodes
        return nodes
            .sortedWith(
                compareByDescending<NodeSummary> { it.isVisibleToUser }
                    .thenByDescending { it.bounds.area }
            )
            .take(maxNodes)
            .sortedBy { it.nodeId }
    }
}
