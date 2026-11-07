package com.swarmcursors.core.metrics

import com.swarmcursors.core.model.SwarmMetrics
import java.util.ArrayDeque

/**
 * Thread-safe collector backing `SwarmCursors.getMetrics()` (spec §8.2).
 * All durations are in milliseconds.
 */
class MetricsCollector(private val frameWindow: Int = 120) {

    private val lock = Any()

    private var totalActions = 0L
    private var succeededActions = 0L
    private var failedActions = 0L

    private var motionCount = 0L
    private var motionSumMs = 0.0

    private var executionCount = 0L
    private var executionSumMs = 0.0

    private var snapshotCount = 0L
    private var snapshotSumMs = 0.0

    private val recentFrameTimesMs = ArrayDeque<Float>()

    /** Called once per finished action (success or failure). */
    fun recordActionResult(succeeded: Boolean) {
        synchronized(lock) {
            totalActions++
            if (succeeded) succeededActions++ else failedActions++
        }
    }

    fun recordMotion(durationMs: Long) {
        synchronized(lock) {
            motionCount++
            motionSumMs += durationMs
        }
    }

    fun recordExecution(durationMs: Long) {
        synchronized(lock) {
            executionCount++
            executionSumMs += durationMs
        }
    }

    fun recordSnapshot(durationMs: Long) {
        synchronized(lock) {
            snapshotCount++
            snapshotSumMs += durationMs
        }
    }

    /** Called by the overlay every rendered frame with the frame delta. */
    fun recordFrame(frameTimeMs: Float) {
        synchronized(lock) {
            recentFrameTimesMs.addLast(frameTimeMs)
            while (recentFrameTimesMs.size > frameWindow) {
                recentFrameTimesMs.removeFirst()
            }
        }
    }

    /** Immutable point-in-time snapshot of the collected metrics. */
    fun snapshot(): SwarmMetrics = synchronized(lock) {
        val avgFrame = if (recentFrameTimesMs.isEmpty()) {
            0f
        } else {
            recentFrameTimesMs.sum() / recentFrameTimesMs.size
        }
        val fps = if (avgFrame > 0f) (1000f / avgFrame).toInt() else 0
        SwarmMetrics(
            totalActions = totalActions,
            succeeded = succeededActions,
            failed = failedActions,
            avgMotionDurationMs = avg(motionCount, motionSumMs),
            avgExecutionDurationMs = avg(executionCount, executionSumMs),
            avgSnapshotDurationMs = avg(snapshotCount, snapshotSumMs),
            currentFps = fps,
            avgFrameTimeMs = avgFrame,
        )
    }

    /** Clears every counter (spec §8.2 `resetMetrics`). */
    fun reset() {
        synchronized(lock) {
            totalActions = 0L
            succeededActions = 0L
            failedActions = 0L
            motionCount = 0L
            motionSumMs = 0.0
            executionCount = 0L
            executionSumMs = 0.0
            snapshotCount = 0L
            snapshotSumMs = 0.0
            recentFrameTimesMs.clear()
        }
    }

    private fun avg(count: Long, sum: Double): Long =
        if (count == 0L) 0L else (sum / count).toLong()
}
