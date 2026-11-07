package com.swarmcursors

import com.swarmcursors.core.model.ActionEvent
import com.swarmcursors.core.model.ActionTarget
import com.swarmcursors.core.model.ActionType
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.CancelStage
import com.swarmcursors.test.SwarmTestSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec §3 acceptance: submit 3 tickets, cancel the second — the second flow
 * emits Cancelled, the first and third complete normally.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CancellationTest {

    private fun click(id: String) =
        AgentAction(type = ActionType.CLICK, target = ActionTarget.node(id))

    @Test
    fun `cancelling second of three tickets cancels only it`() = runEngineTest {
        SwarmTestSupport.installMockService(
            listOf(
                testNode("n0"),
                testNode("n1", top = 1000f, bottom = 1080f),
                testNode("n2", top = 1200f, bottom = 1280f),
            )
        )

        // Sequential mode (default): tickets queue up in order.
        val flow0 = SwarmCursors.injectActions(listOf(click("n0")))
        val flow1 = SwarmCursors.injectActions(listOf(click("n1")))
        val flow2 = SwarmCursors.injectActions(listOf(click("n2")))

        val pending = SwarmCursors.getPendingTickets()
        assertEquals(3, pending.size)

        // Cancel the second ticket while it is still queued.
        assertTrue(SwarmCursors.cancelTicket(pending[1].id))
        // Cancelling an already-finished/cancelled ticket is ignored.
        assertFalse(SwarmCursors.cancelTicket(pending[1].id))
        // Unknown ticket ids report false.
        assertFalse(SwarmCursors.cancelTicket("no-such-ticket"))

        val collected = List(3) { mutableListOf<ActionEvent>() }
        val flows = listOf(flow0, flow1, flow2)
        val jobs = List(3) { index ->
            launch { flows[index].collect { collected[index].add(it) } }
        }
        jobs.forEach { it.join() }

        // Ticket 1: cancelled while queued.
        val cancelledEvents = collected[1].filterIsInstance<ActionEvent.Cancelled>()
        assertEquals(1, cancelledEvents.size)
        assertEquals(CancelStage.QUEUED, cancelledEvents.first().stage)
        assertTrue(collected[1].none { it is ActionEvent.Executed })
        assertTrue(collected[1].last() is ActionEvent.TicketCompleted)

        // Tickets 0 and 2 executed normally.
        assertTrue(collected[0].any { it is ActionEvent.Executed })
        assertTrue(collected[2].any { it is ActionEvent.Executed })

        // The engine recorded only the executed actions.
        assertEquals(
            listOf("n0", "n2"),
            SwarmTestSupport.getExecutedActions().map { it.target.nodeId },
        )
    }

    @Test
    fun `cancelAll cancels everything queued`() = runEngineTest {
        SwarmTestSupport.installMockService(
            listOf(
                testNode("n0"),
                testNode("n1", top = 1000f, bottom = 1080f),
            )
        )

        val flow0 = SwarmCursors.injectActions(listOf(click("n0")))
        val flow1 = SwarmCursors.injectActions(listOf(click("n1")))

        SwarmCursors.cancelAll()
        assertEquals(0, SwarmCursors.getPendingTickets().size)

        val collected0 = mutableListOf<ActionEvent>()
        val collected1 = mutableListOf<ActionEvent>()
        val jobs = listOf(
            launch { flow0.collect { collected0.add(it) } },
            launch { flow1.collect { collected1.add(it) } },
        )
        jobs.forEach { it.join() }

        assertTrue(collected0.any { it is ActionEvent.Cancelled })
        assertTrue(collected1.any { it is ActionEvent.Cancelled })
        assertTrue(SwarmTestSupport.getExecutedActions().isEmpty())
    }

    @Test
    fun `cancelling a running ticket interrupts motion`() = runEngineTest {
        SwarmTestSupport.installMockService(listOf(testNode("far_target", top = 2000f, bottom = 2080f)))

        val events = mutableListOf<ActionEvent>()
        val job = launch {
            SwarmCursors.injectActions(listOf(click("far_target"))).collect { events.add(it) }
        }

        // Let the motion start (a few frames of virtual time).
        advanceTimeBy(120)
        val running = SwarmCursors.getPendingTickets()
        assertTrue(running.isNotEmpty())
        assertTrue(SwarmCursors.cancelTicket(running.first().id))
        job.join()

        assertTrue(events.any { it is ActionEvent.Moving })
        val cancelled = events.filterIsInstance<ActionEvent.Cancelled>()
        assertTrue(cancelled.isNotEmpty())
        assertTrue(
            "Cancel must happen in MOVING or EXECUTING stage",
            cancelled.all { it.stage == CancelStage.MOVING || it.stage == CancelStage.EXECUTING },
        )
        assertTrue(events.none { it is ActionEvent.Executed })
    }
}
