package com.swarmcursors

import com.swarmcursors.core.model.ActionEvent
import com.swarmcursors.core.model.ActionTarget
import com.swarmcursors.core.model.ActionType
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.AuditEntry
import com.swarmcursors.core.model.SnapshotConfig
import com.swarmcursors.test.SwarmTestSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec §10 acceptance: password nodes are redacted in snapshots and the
 * audit log never contains typed text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotSecurityTest {

    @Test
    fun `password nodes are redacted in snapshots and audit`() = runEngineTest {
        SwarmTestSupport.installMockService(
            listOf(
                testNode("pwd", text = "hunter2", password = true, top = 900f, bottom = 980f),
                testNode("name", text = "Alice"),
            )
        )

        // --- Snapshot redaction (§10.1) ---
        val snapshot = SwarmCursors.captureSnapshot(
            SnapshotConfig(settleDelayMs = 0, stabilityIntervalMs = 0)
        )
        assertNotNull(snapshot)
        val nodes = snapshot!!.nodes
        val pwd = nodes.first { it.nodeId == "pwd" }
        assertNull("isPassword nodes must be captured without text", pwd.text)
        assertEquals("Alice", nodes.first { it.nodeId == "name" }.text)

        // --- Audit redaction (§10.2) ---
        val audit = mutableListOf<AuditEntry>()
        SwarmCursors.setAuditListener { audit.add(it) }
        try {
            val events = mutableListOf<ActionEvent>()
            SwarmCursors.injectActions(
                listOf(
                    AgentAction(
                        type = ActionType.TYPE,
                        target = ActionTarget.node("pwd"),
                        text = "super-secret",
                    )
                )
            ).collect { events.add(it) }

            assertTrue(events.any { it is ActionEvent.Executed })
            assertEquals(1, audit.size)
            assertNull("Audit entries never carry typed text", audit.first().action.text)
            assertEquals(ActionType.TYPE, audit.first().action.type)
            assertTrue(audit.first().result)
        } finally {
            SwarmCursors.setAuditListener(null)
        }
    }

    @Test
    fun `masked password input type keeps placeholder mask`() = runEngineTest {
        val passwordTyped = testNode("pwd_masked", text = "secret").copy(
            isPassword = false,
            inputType = com.swarmcursors.core.model.InputTypes.CLASS_TEXT or
                com.swarmcursors.core.model.InputTypes.VARIATION_PASSWORD,
        )
        SwarmTestSupport.installMockService(listOf(passwordTyped))

        val snapshot = SwarmCursors.captureSnapshot(
            SnapshotConfig(settleDelayMs = 0, stabilityIntervalMs = 0)
        )
        assertNotNull(snapshot)
        assertEquals("***", snapshot!!.nodes.first { it.nodeId == "pwd_masked" }.text)
    }

    @Test
    fun `includeSensitiveText keeps original content`() = runEngineTest {
        SwarmTestSupport.installMockService(listOf(testNode("pwd", text = "hunter2", password = true)))

        val snapshot = SwarmCursors.captureSnapshot(
            SnapshotConfig(settleDelayMs = 0, stabilityIntervalMs = 0, includeSensitiveText = true)
        )
        assertNotNull(snapshot)
        assertEquals("hunter2", snapshot!!.nodes.first { it.nodeId == "pwd" }.text)
    }

    @Test
    fun `diff reports added and removed nodes after interface change`() = runEngineTest {
        SwarmTestSupport.installMockService(
            listOf(testNode("old_button", "Back"), testNode("title", "Settings", top = 600f, bottom = 660f))
        )

        val first = SwarmCursors.captureSnapshot(
            SnapshotConfig(settleDelayMs = 0, stabilityIntervalMs = 0)
        )
        assertNotNull(first)
        assertNull("First snapshot has no diff", first!!.diff)

        // Navigate: title stays, old button disappears, new button appears.
        SwarmTestSupport.simulateInterfaceChange(
            listOf(testNode("title", "Settings", top = 600f, bottom = 660f), testNode("new_button", "Next"))
        )
        val second = SwarmCursors.captureSnapshot(
            SnapshotConfig(settleDelayMs = 0, stabilityIntervalMs = 0)
        )
        assertNotNull(second)
        val diff = second!!.diff
        assertNotNull(diff)
        assertEquals(listOf("new_button"), diff!!.added.map { it.nodeId })
        assertEquals(listOf("old_button"), diff.removed)
    }

    @Test
    fun `maxNodes truncation keeps visible nodes`() = runEngineTest {
        val manyNodes = (0 until 60).map { index ->
            testNode(
                id = "node$index",
                top = 500f + index * 10f,
                bottom = 540f + index * 10f,
                visible = index % 2 == 0,
            )
        }
        SwarmTestSupport.installMockService(manyNodes)

        val snapshot = SwarmCursors.captureSnapshot(
            SnapshotConfig(settleDelayMs = 0, stabilityIntervalMs = 0, maxNodes = 10)
        )
        assertNotNull(snapshot)
        val nodes = snapshot!!.nodes
        // Root + truncated children.
        assertTrue("Expected truncation to ~10 nodes, got ${nodes.size}", nodes.size <= 10)
        assertTrue("Visible nodes must survive truncation", nodes.any { it.nodeId.startsWith("node") })
    }
}
