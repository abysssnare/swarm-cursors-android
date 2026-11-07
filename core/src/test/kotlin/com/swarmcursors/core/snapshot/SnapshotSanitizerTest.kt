package com.swarmcursors.core.snapshot

import com.swarmcursors.core.model.InputTypes
import com.swarmcursors.core.model.NodeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotSanitizerTest {

    @Test
    fun `password input type text becomes mask`() {
        val node = NodeSummary(
            nodeId = "pwd",
            text = "hunter2",
            inputType = InputTypes.CLASS_TEXT or InputTypes.VARIATION_PASSWORD,
        )
        val sanitized = SnapshotSanitizer.sanitize(listOf(node), includeSensitiveText = false)
        assertEquals(SnapshotSanitizer.MASK, sanitized.first().text)
    }

    @Test
    fun `isPassword nodes are captured without text`() {
        val node = NodeSummary(nodeId = "pwd", text = "hunter2", isPassword = true)
        val sanitized = SnapshotSanitizer.sanitize(listOf(node), includeSensitiveText = false)
        assertNull(sanitized.first().text)
    }

    @Test
    fun `visible password variant is also masked`() {
        val node = NodeSummary(
            nodeId = "pwd",
            text = "hunter2",
            inputType = InputTypes.CLASS_TEXT or InputTypes.VARIATION_VISIBLE_PASSWORD,
        )
        val sanitized = SnapshotSanitizer.sanitize(listOf(node), includeSensitiveText = false)
        assertEquals(SnapshotSanitizer.MASK, sanitized.first().text)
    }

    @Test
    fun `normal nodes are untouched and list is not copied`() {
        val nodes = listOf(NodeSummary(nodeId = "a", text = "hello"))
        val sanitized = SnapshotSanitizer.sanitize(nodes, includeSensitiveText = false)
        assertSame(nodes, sanitized)
        assertEquals("hello", sanitized.first().text)
    }

    @Test
    fun `includeSensitiveText keeps original text`() {
        val node = NodeSummary(
            nodeId = "pwd",
            text = "hunter2",
            inputType = InputTypes.CLASS_TEXT or InputTypes.VARIATION_PASSWORD,
        )
        val sanitized = SnapshotSanitizer.sanitize(listOf(node), includeSensitiveText = true)
        assertEquals("hunter2", sanitized.first().text)
    }

    @Test
    fun `stability detector ignores small jitter`() {
        val a = listOf(NodeSummary(nodeId = "x", bounds = com.swarmcursors.core.geometry.RectF(0f, 0f, 100f, 100f)))
        val b = listOf(NodeSummary(nodeId = "x", bounds = com.swarmcursors.core.geometry.RectF(1f, 2f, 100f, 100f)))
        assertTrue(SnapshotStability.isStable(a, b, thresholdPx = 5f))
    }

    @Test
    fun `stability detector flags animating ui`() {
        val a = listOf(NodeSummary(nodeId = "x", bounds = com.swarmcursors.core.geometry.RectF(0f, 0f, 100f, 100f)))
        val b = listOf(NodeSummary(nodeId = "x", bounds = com.swarmcursors.core.geometry.RectF(30f, 40f, 130f, 140f)))
        assertEquals(false, SnapshotStability.isStable(a, b, thresholdPx = 5f))
    }

    @Test
    fun `stability detector requires same node count`() {
        val a = listOf(NodeSummary(nodeId = "x"))
        val b = listOf(NodeSummary(nodeId = "x"), NodeSummary(nodeId = "y"))
        assertEquals(false, SnapshotStability.isStable(a, b, thresholdPx = 5f))
    }
}
