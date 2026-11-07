package com.swarmcursors.test

import com.swarmcursors.SwarmCursors
import com.swarmcursors.core.model.ActionType
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.NodeSummary

/**
 * Test support for integrators (spec §9.1). Installs a mock accessibility
 * service so UI flows and engine logic can be exercised without a real
 * accessibility permission — ideal for Robolectric/JVM tests and demos.
 *
 * ```kotlin
 * SwarmTestSupport.installMockService(listOf(NodeSummary(nodeId = "submit", ...)))
 * runTest {
 *     SwarmCursors.injectActions(listOf(AgentAction(ActionType.CLICK, ActionTarget.node("submit"))))
 *         .collect { println(it) }
 * }
 * assertEquals(1, SwarmTestSupport.getExecutedActions().size)
 * SwarmTestSupport.reset()
 * ```
 */
object SwarmTestSupport {

    /**
     * Installs the mock service. Node ids are the test-provided ids (the
     * path-based `r/…` scheme of the real host does not apply).
     *
     * @param actionResults per-action-type result the mock reports from
     * `performAction`; types absent from the map report success.
     */
    @JvmStatic
    fun installMockService(
        nodes: List<NodeSummary>,
        actionResults: Map<ActionType, Boolean> = emptyMap(),
    ) {
        SwarmCursors.installMockHost(nodes, actionResults)
    }

    /**
     * Replaces the mock node tree (simulates navigation, content change or
     * a stale-node scenario for §4.3 retry tests).
     */
    @JvmStatic
    fun simulateInterfaceChange(newNodes: List<NodeSummary>) {
        val host = SwarmCursors.mockHost()
        checkNotNull(host) { "Mock service is not installed" }
        host.updateNodes(newNodes)
    }

    /** Actions dispatched so far, in order. */
    @JvmStatic
    fun getExecutedActions(): List<AgentAction> =
        SwarmCursors.mockHost()?.executedActions() ?: emptyList()

    /** Clears executed-action history and removes the mock. */
    @JvmStatic
    fun reset() {
        SwarmCursors.clearMockHost()
    }

    /** Alias of [reset]. */
    @JvmStatic
    fun uninstall() = reset()
}
