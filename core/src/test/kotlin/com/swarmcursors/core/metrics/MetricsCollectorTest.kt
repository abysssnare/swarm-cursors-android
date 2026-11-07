package com.swarmcursors.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsCollectorTest {

    @Test
    fun `counters aggregate results`() {
        val collector = MetricsCollector()
        repeat(3) { collector.recordActionResult(succeeded = true) }
        repeat(2) { collector.recordActionResult(succeeded = false) }

        val metrics = collector.snapshot()
        assertEquals(5L, metrics.totalActions)
        assertEquals(3L, metrics.succeeded)
        assertEquals(2L, metrics.failed)
    }

    @Test
    fun `averages are rounded to milliseconds`() {
        val collector = MetricsCollector()
        collector.recordMotion(100)
        collector.recordMotion(50)
        collector.recordExecution(33)
        collector.recordSnapshot(90)

        val metrics = collector.snapshot()
        assertEquals(75L, metrics.avgMotionDurationMs)
        assertEquals(33L, metrics.avgExecutionDurationMs)
        assertEquals(90L, metrics.avgSnapshotDurationMs)
    }

    @Test
    fun `fps derives from the recent frame window`() {
        val collector = MetricsCollector(frameWindow = 10)
        repeat(10) { collector.recordFrame(16.6f) }

        val metrics = collector.snapshot()
        assertEquals(60, metrics.currentFps)
        assertTrue(metrics.avgFrameTimeMs > 16f)
    }

    @Test
    fun `frame window evicts old samples`() {
        val collector = MetricsCollector(frameWindow = 4)
        repeat(10) { collector.recordFrame(100f) }
        repeat(4) { collector.recordFrame(10f) }

        val metrics = collector.snapshot()
        // Only the last 4 frames (10 ms each) count → 100 fps.
        assertEquals(100, metrics.currentFps)
    }

    @Test
    fun `reset clears everything`() {
        val collector = MetricsCollector()
        collector.recordActionResult(succeeded = true)
        collector.recordFrame(16f)
        collector.reset()

        val metrics = collector.snapshot()
        assertEquals(0L, metrics.totalActions)
        assertEquals(0, metrics.currentFps)
        assertEquals(0f, metrics.avgFrameTimeMs, 0f)
    }
}
