package com.swarmcursors

import com.swarmcursors.core.model.ActionEvent
import com.swarmcursors.core.model.ActionTarget
import com.swarmcursors.core.model.ActionType
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.FailureReason
import com.swarmcursors.core.model.ServiceState
import com.swarmcursors.test.SwarmTestSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec §9.1 acceptance: under the mock service, `injectActions` walks the
 * full Moving → Executed flow and records executed actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockServiceFlowTest {

    @Test
    fun `mock service walks moving to executed`() = runEngineTest {
        SwarmTestSupport.installMockService(listOf(testNode("btn_ok", "OK")))

        assertEquals(ServiceState.Ready, SwarmCursors.getServiceState())

        val events = mutableListOf<ActionEvent>()
        SwarmCursors.injectActions(
            listOf(AgentAction(type = ActionType.CLICK, target = ActionTarget.node("btn_ok")))
        ).collect { events.add(it) }

        assertTrue("Expected Moving events", events.any { it is ActionEvent.Moving })
        assertTrue("Expected Executing events", events.any { it is ActionEvent.Executing })
        val executed = events.filterIsInstance<ActionEvent.Executed>()
        assertEquals(1, executed.size)
        assertTrue(executed.first().result)
        assertTrue(events.last() is ActionEvent.TicketCompleted)

        val recorded = SwarmTestSupport.getExecutedActions()
        assertEquals(listOf(ActionType.CLICK), recorded.map { it.type })
    }

    @Test
    fun `typing reaches the node with set text`() = runEngineTest {
        SwarmTestSupport.installMockService(listOf(testNode("input_name", left = 100f, top = 900f)))

        val events = mutableListOf<ActionEvent>()
        SwarmCursors.injectActions(
            listOf(
                AgentAction(
                    type = ActionType.TYPE,
                    target = ActionTarget.node("input_name"),
                    text = "hello swarm",
                )
            )
        ).collect { events.add(it) }

        assertTrue(events.any { it is ActionEvent.Executed })
        val recorded = SwarmTestSupport.getExecutedActions()
        assertEquals(1, recorded.size)
        assertEquals("hello swarm", recorded.first().text)
    }

    @Test
    fun `multiple actions in one ticket run sequentially`() = runEngineTest {
        SwarmTestSupport.installMockService(
            listOf(testNode("a"), testNode("b", top = 1000f, bottom = 1080f))
        )

        val events = mutableListOf<ActionEvent>()
        SwarmCursors.injectActions(
            listOf(
                AgentAction(type = ActionType.CLICK, target = ActionTarget.node("a")),
                AgentAction(type = ActionType.CLICK, target = ActionTarget.node("b")),
            )
        ).collect { events.add(it) }

        val executed = events.filterIsInstance<ActionEvent.Executed>()
        assertEquals(2, executed.size)
        assertEquals(
            listOf("a", "b"),
            SwarmTestSupport.getExecutedActions().map { it.target.nodeId },
        )
        val completed = events.last() as ActionEvent.TicketCompleted
        assertEquals(2, completed.succeeded)
    }

    @Test
    fun `service not ready returns immediate failure without throwing`() = runEngineTest {
        // No mock installed and no real service: state must not be Ready.
        val state = SwarmCursors.getServiceState()
        assertTrue(state is ServiceState.NotEnabled || state is ServiceState.Connecting)

        val events = mutableListOf<ActionEvent>()
        SwarmCursors.injectActions(
            listOf(AgentAction(type = ActionType.CLICK, target = ActionTarget.node("x")))
        ).collect { events.add(it) }

        assertEquals(1, events.size)
        val failed = events.first() as ActionEvent.Failed
        assertEquals(FailureReason.SERVICE_NOT_READY, failed.reason)
        assertNotNull(failed.message)
    }
}
