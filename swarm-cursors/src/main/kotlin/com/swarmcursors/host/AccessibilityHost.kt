package com.swarmcursors.host

import android.accessibilityservice.AccessibilityService
import com.swarmcursors.core.geometry.RectF
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.NodeSummary
import com.swarmcursors.core.model.WindowTarget

/** System-level actions that are not bound to a specific node. */
enum class GlobalAction(val code: Int) {
    BACK(AccessibilityService.GLOBAL_ACTION_BACK),
    HOME(AccessibilityService.GLOBAL_ACTION_HOME),
    RECENTS(AccessibilityService.GLOBAL_ACTION_RECENTS),
    NOTIFICATIONS(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
    QUICK_SETTINGS(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
}

/** Outcome of dispatching the real accessibility action. */
sealed class ExecutionOutcome {
    /** The action was dispatched; [success] is the system's answer. */
    data class Completed(val success: Boolean, val durationMs: Long) : ExecutionOutcome()

    /** The target node was stale (§4.3); nothing was performed. */
    object Stale : ExecutionOutcome()

    /** The host (service) is not connected. */
    object NotReady : ExecutionOutcome()
}

/**
 * Abstraction over one accessibility node. The real implementation wraps
 * [android.accessibilityservice.AccessibilityNodeInfo]; tests provide mock
 * implementations backed by [NodeSummary] trees (§9.1).
 */
interface NodeHandle {
    fun isVisibleToUser(): Boolean
    fun isEnabled(): Boolean
    fun isPassword(): Boolean
    fun boundsInScreen(): RectF
    fun childCount(): Int
    fun child(index: Int): NodeHandle?

    /** Re-fetches the node state from the system. */
    fun refresh(): Boolean

    /** Performs a node-scoped action. Returns the system's boolean answer. */
    fun performAction(action: AgentAction): Boolean

    /** Converts the node into a [NodeSummary] for snapshots. */
    fun toSummary(nodeId: String, parentId: String?, depth: Int): NodeSummary
}

/**
 * Seam between the engine and the accessibility backend (§9.1). Production
 * code uses [RealAccessibilityHost]; [com.swarmcursors.test.SwarmTestSupport]
 * installs a mock implementation.
 */
interface AccessibilityHost {

    fun isReady(): Boolean

    /** Non-PiP windows (spec §6.1/§6.2). */
    fun windows(): List<WindowTarget>

    /** Root node of the active window or of [windowId] when set. */
    fun rootInWindow(windowId: Int?): NodeHandle?

    /** Resolves a node id (path form `r/0/2` for the real host). */
    fun findNodeById(nodeId: String, windowId: Int?): NodeHandle? {
        val root = rootInWindow(windowId) ?: return null
        return NodePathResolver.resolve(root, nodeId)
    }

    fun performGlobalAction(action: GlobalAction): Boolean

    /** Dispatches a synthetic tap at screen coordinates (gesture fallback). */
    fun injectTap(x: Float, y: Float): Boolean
}

/**
 * Path-based node id scheme used by the real host: the root is `r` and the
 * i-th child of node `p` is `p/i`.
 */
object NodePathResolver {

    const val ROOT_ID = "r"

    fun resolve(root: NodeHandle, nodeId: String): NodeHandle? {
        if (nodeId == ROOT_ID) return root
        if (!nodeId.startsWith("$ROOT_ID/")) return null
        var current: NodeHandle = root
        val segments = nodeId.removePrefix(ROOT_ID).split('/')
        for (segment in segments) {
            if (segment.isEmpty()) continue
            val index = segment.toIntOrNull() ?: return null
            val next = current.child(index) ?: return null
            current = next
        }
        return current
    }

    fun childId(parentId: String, index: Int): String = "$parentId/$index"
}
