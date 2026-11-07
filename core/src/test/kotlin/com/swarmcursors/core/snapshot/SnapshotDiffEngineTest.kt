package com.swarmcursors.core.snapshot

import com.swarmcursors.core.geometry.RectF
import com.swarmcursors.core.model.NodeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotDiffEngineTest {

    private fun node(
        id: String,
        text: String? = null,
        left: Float = 0f,
        top: Float = 0f,
        right: Float = 100f,
        bottom: Float = 100f,
    ) = NodeSummary(nodeId = id, text = text, bounds = RectF(left, top, right, bottom))

    @Test
    fun `added and removed nodes are reported`() {
        val before = listOf(node("a"), node("b"), node("c"))
        val after = listOf(node("a"), node("c"), node("d"))

        val diff = SnapshotDiffEngine.compute(before, after)

        assertEquals(listOf("d"), diff.added.map { it.nodeId })
        assertEquals(listOf("b"), diff.removed)
        assertTrue(diff.changed.isEmpty())
    }

    @Test
    fun `bounds movement beyond epsilon is a change`() {
        val before = listOf(node("a", left = 0f, top = 0f, right = 100f, bottom = 100f))
        val after = listOf(node("a", left = 3f, top = 4f, right = 100f, bottom = 100f))

        val diff = SnapshotDiffEngine.compute(before, after)

        assertEquals(1, diff.changed.size)
        assertEquals("a", diff.changed.first().nodeId)
    }

    @Test
    fun `sub-epsilon jitter is not a change`() {
        val before = listOf(node("a", left = 0f, top = 0f))
        val after = listOf(node("a", left = 0.4f, top = 0.3f))

        val diff = SnapshotDiffEngine.compute(before, after)

        assertTrue(diff.changed.isEmpty())
    }

    @Test
    fun `text change is a change`() {
        val before = listOf(node("a", text = "Submit"))
        val after = listOf(node("a", text = "Sent!"))

        val diff = SnapshotDiffEngine.compute(before, after)

        assertEquals(1, diff.changed.size)
    }

    @Test
    fun `first snapshot has no counterpart so everything is added`() {
        val after = listOf(node("a"), node("b"))
        val diff = SnapshotDiffEngine.compute(emptyList(), after)
        assertEquals(2, diff.added.size)
        assertTrue(diff.removed.isEmpty())
    }
}
