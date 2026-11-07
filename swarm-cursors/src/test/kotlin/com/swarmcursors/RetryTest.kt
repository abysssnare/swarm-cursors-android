package com.swarmcursors

import com.swarmcursors.core.model.ActionEvent
import com.swarmcursors.core.model.ActionTarget
import com.swarmcursors.core.model.ActionType
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.FailureReason
import com.swarmcursors.test.SwarmTestSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec §4 acceptance: rejected executions retry once; stale nodes are
 * re-resolved and recover after an interface change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetryTest {

    @Test
    fun `execution rejection retries once then fails with attempts`() = runEngineTest {
        SwarmTestSupport.installMockService(
            nodes = listOf(testNode("btn")),
            actionResults = mapOf(ActionType.CLICK to false),
        )

        val events = mutableListOf<ActionEvent>()
        SwarmCursors.injectActions(
            listOf(AgentAction(type = ActionType.CLICK, target = ActionTarget.node("btn")))
        ).collect { events.add(it) }

        val failed = events.filterIsInstance<ActionEvent.Failed>()
        assertEquals(1, failed.size)
        assertEquals(FailureReason.EXECUTION_REJECTED, failed.first().reason)
        assertEquals(2, failed.first().attempts)

        // Two real attempts were dispatched and audited.
        assertEquals(2, SwarmTestSupport.getExecutedActions().size)
        val completed = events.last() as ActionEvent.TicketCompleted
        assertEquals(1, completed.failed)
    }

    @Test
    fun `stale node recovers after interface change`() = runEngineTest {
        // Node starts disabled: resolve reports NODE_STALE (spec §4.3).
        SwarmTestSupport.installMockService(listOf(testNode("btn", enabled = false)))

        val events = mutableListOf<ActionEvent>()
        val job = launch {
            SwarmCursors.injectActions(
                listOf(AgentAction(type = ActionType.CLICK, target = ActionTarget.node("btn")))
            ).collect { events.add(it) }
        }

        // The first resolve fails and schedules a retry after the backoff.
        advanceTimeBy(50)
        // Simulate the interface settling (e.g. animation finished).
        SwarmTestSupport.simulateInterfaceChange(listOf(testNode("btn", enabled = true)))

        job.join()

        assertTrue(events.any { it is ActionEvent.Executed })
        assertTrue(events.none { it is ActionEvent.Failed })
        assertEquals(1, SwarmTestSupport.getExecutedActions().size)
    }

    @Test
    fun `stale node without recovery fails with NODE_STALE`() = runEngineTest {
        SwarmTestSupport.installMockService(listOf(testNode("btn", enabled = false)))

        val events = mutableListOf<ActionEvent>()
        SwarmCursors.injectActions(
            listOf(AgentAction(type = ActionType.CLICK, target = ActionTarget.node("btn")))
        ).collect { events.add(it) }

        val failed = events.filterIsInstance<ActionEvent.Failed>()
        assertEquals(1, failed.size)
        assertEquals(FailureReason.NODE_STALE, failed.first().reason)
        assertEquals(2, failed.first().attempts)
        assertTrue(SwarmTestSupport.getExecutedActions().isEmpty())
    }

    @Test
    fun `missing node without coordinates fails with TARGET_NOT_FOUND`() = runEngineTest {
        SwarmTestSupport.installMockService(listOf(testNode("btn")))

        val events = mutableListOf<ActionEvent>()
        SwarmCursors.injectActions(
            listOf(AgentAction(type = ActionType.CLICK, target = ActionTarget.node("ghost")))
        ).collect { events.add(it) }

        val failed = events.filterIsInstance<ActionEvent.Failed>()
        assertEquals(1, failed.size)
        assertEquals(FailureReason.TARGET_NOT_FOUND, failed.first().reason)
        assertEquals(1, failed.first().attempts) // not retryable by default
    }
}
