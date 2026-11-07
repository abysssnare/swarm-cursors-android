package com.swarmcursors.test

import com.swarmcursors.core.geometry.PointF
import com.swarmcursors.core.geometry.RectF
import com.swarmcursors.core.log.SwarmLog
import com.swarmcursors.core.model.ActionType
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.NodeSummary
import com.swarmcursors.core.model.WindowTarget
import com.swarmcursors.host.AccessibilityHost
import com.swarmcursors.host.GlobalAction
import com.swarmcursors.host.NodeHandle
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Mock [AccessibilityHost] used by [SwarmTestSupport] (spec §9.1). Nodes are
 * supplied by the test as flat [NodeSummary] lists with arbitrary ids; the
 * synthetic root exposes them as children so snapshot captures include them.
 */
internal class MockAccessibilityHost(
    initialNodes: List<NodeSummary>,
) : AccessibilityHost {

    @Volatile
    private var nodes: List<NodeSummary> = initialNodes

    @Volatile
    var actionResults: Map<ActionType, Boolean> = emptyMap()

    private val executed = CopyOnWriteArrayList<AgentAction>()

    fun updateNodes(newNodes: List<NodeSummary>) {
        nodes = newNodes
    }

    fun executedActions(): List<AgentAction> = executed.toList()

    fun reset() {
        executed.clear()
    }

    override fun isReady(): Boolean = true

    override fun windows(): List<WindowTarget> = listOf(
        WindowTarget(
            windowId = MOCK_WINDOW_ID,
            title = "MockWindow",
            bounds = RectF(0f, 0f, 1080f, 2400f),
            isActive = true,
        )
    )

    override fun rootInWindow(windowId: Int?): NodeHandle =
        MockNodeHandle(
            summary = NodeSummary(
                nodeId = "r",
                className = "android.widget.FrameLayout",
                bounds = RectF(0f, 0f, 1080f, 2400f),
                isVisibleToUser = true,
            ),
            host = this,
        )

    override fun findNodeById(nodeId: String, windowId: Int?): NodeHandle? {
        // Mock nodes use their own ids (not path ids).
        val node = nodes.firstOrNull { it.nodeId == nodeId } ?: return null
        return MockNodeHandle(node, this)
    }

    override fun performGlobalAction(action: GlobalAction): Boolean {
        SwarmLog.d("MockAccessibilityHost.performGlobalAction($action)")
        return true
    }

    override fun injectTap(x: Float, y: Float): Boolean {
        // The mock does not simulate gestures — coordinate fallbacks report
        // failure so EXECUTION_REJECTED semantics stay testable (spec §4.2).
        SwarmLog.d("MockAccessibilityHost.injectTap($x, $y) -> false (not simulated)")
        return false
    }

    fun recordExecution(action: AgentAction) {
        executed.add(action)
    }

    fun currentNodes(): List<NodeSummary> = nodes

    companion object {
        const val MOCK_WINDOW_ID = 42

        /** Screen size reported to the engine through the mock stage. */
        val MOCK_SCREEN = PointF(1080f, 2400f)
    }
}

/**
 * Mock node backed by a [NodeSummary]. Children of the synthetic root are
 * the test-supplied nodes.
 */
internal class MockNodeHandle(
    private val summary: NodeSummary,
    private val host: MockAccessibilityHost,
) : NodeHandle {

    private val isRoot: Boolean = summary.nodeId == "r"

    override fun isVisibleToUser(): Boolean = summary.isVisibleToUser

    override fun isEnabled(): Boolean = summary.isEnabled

    override fun isPassword(): Boolean = summary.isPassword

    override fun boundsInScreen(): RectF = summary.bounds

    override fun childCount(): Int = if (isRoot) host.currentNodes().size else 0

    override fun child(index: Int): NodeHandle? {
        if (!isRoot) return null
        val children = host.currentNodes()
        if (index < 0 || index >= children.size) return null
        return MockNodeHandle(children[index], host)
    }

    override fun refresh(): Boolean = true

    override fun performAction(action: AgentAction): Boolean {
        host.recordExecution(action)
        val result = host.actionResults[action.type] ?: true
        SwarmLog.d("MockNodeHandle.performAction(${action.type}) -> $result")
        return result
    }

    override fun toSummary(nodeId: String, parentId: String?, depth: Int): NodeSummary =
        // Mock nodes keep their own ids (spec §9.1 uses arbitrary ids).
        summary.copy(nodeId = summary.nodeId, parentId = parentId, depth = depth)
}
