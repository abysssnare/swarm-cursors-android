package com.swarmcursors.core.motion

import com.swarmcursors.core.geometry.PointF
import com.swarmcursors.core.model.MotionStyle

/**
 * Assertion helpers for motion planning (spec §9.2). Ships in `:core` so
 * integrators and CI can validate the engine contract:
 *
 *  * interpolation continuity (adjacent samples stay within a distance budget)
 *  * Fitts' law monotonicity (longer distance ⇒ longer duration)
 *  * spring landing without visible oscillation (overshoot < 10%)
 *
 * All methods throw [AssertionError] on violation and return normally on
 * success so they can be used from plain JUnit tests.
 */
class MotionPlannerTestHarness(
    private val planner: MotionPlanner = MotionPlanner(),
) {

    /** Samples a planned path at `steps + 1` evenly spaced `t` values. */
    fun samplePath(
        from: PointF,
        to: PointF,
        style: MotionStyle,
        targetWidthPx: Float = 48f,
        durationHintMs: Long? = null,
        seed: Long = 42L,
        steps: Int = 50,
    ): List<PathSample> {
        val path = planner.plan(from, to, style, targetWidthPx, durationHintMs, seed)
        val samples = ArrayList<PathSample>(steps + 1)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val position = path.positionAt(t)
            samples.add(PathSample(t, position, path.progressAt(t)))
        }
        return samples
    }

    /**
     * Asserts that no two adjacent samples (taken at `steps` intervals over
     * the normalized duration) are further apart than [maxStepDistancePx].
     */
    fun assertInterpolationContinuity(
        from: PointF,
        to: PointF,
        style: MotionStyle,
        maxStepDistancePx: Float,
        targetWidthPx: Float = 48f,
        seed: Long = 42L,
        steps: Int = 50,
    ) {
        val samples = samplePath(from, to, style, targetWidthPx, seed = seed, steps = steps)
        var previous = samples.first()
        for (i in 1 until samples.size) {
            val current = samples[i]
            val step = previous.position.distanceTo(current.position)
            if (step > maxStepDistancePx) {
                throw AssertionError(
                    "Interpolation discontinuity for style $style between t=${previous.t} " +
                        "and t=${current.t}: step=$step px > budget=$maxStepDistancePx px"
                )
            }
            previous = current
        }
    }

    /**
     * Asserts that planned durations grow (weakly) with distance, and that
     * at least the longest distance is strictly slower than the shortest.
     */
    fun assertDurationMonotonic(
        style: MotionStyle,
        targetWidthPx: Float = 48f,
        distancesPx: List<Float> = listOf(100f, 300f, 600f, 1_000f),
        seed: Long = 42L,
    ) {
        require(distancesPx.size >= 2) { "Need at least two distances" }
        val sorted = distancesPx.sorted()
        val origin = PointF(0f, 0f)
        var previousDuration = 0L
        for ((index, distance) in sorted.withIndex()) {
            val to = PointF(distance, 0f)
            val duration = planner.plan(origin, to, style, targetWidthPx, seed = seed).durationMs
            if (index > 0 && duration < previousDuration) {
                throw AssertionError(
                    "Fitts duration decreased with distance for style $style: " +
                        "${sorted[index - 1]}px → ${previousDuration}ms, " +
                        "$distance px → ${duration}ms"
                )
            }
            previousDuration = duration
        }
        val shortest = planner.plan(origin, PointF(sorted.first(), 0f), style, targetWidthPx, seed = seed).durationMs
        val longest = planner.plan(origin, PointF(sorted.last(), 0f), style, targetWidthPx, seed = seed).durationMs
        if (longest <= shortest) {
            throw AssertionError(
                "Expected strictly increasing duration between ${sorted.first()}px and ${sorted.last()}px " +
                    "for style $style (got $shortest ms vs $longest ms)"
            )
        }
    }

    /**
     * Asserts the cursor lands on the target without visible oscillation:
     * the projection of every sample onto the from→to axis must stay within
     * [maxOvershootRatio] (default 10%) beyond the target, and the final
     * sample must sit within 1px of the target.
     */
    fun assertNoOvershoot(
        from: PointF,
        to: PointF,
        style: MotionStyle = MotionStyle.SPRING,
        maxOvershootRatio: Float = 0.10f,
        targetWidthPx: Float = 48f,
        seed: Long = 42L,
        steps: Int = 200,
    ) {
        val samples = samplePath(from, to, style, targetWidthPx, seed = seed, steps = steps)
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = kotlin.math.hypot(dx, dy)
        if (length < 1f) return
        val ux = dx / length
        val uy = dy / length
        var maxProjection = Float.NEGATIVE_INFINITY
        for (sample in samples) {
            val projection = (sample.position.x - from.x) * ux + (sample.position.y - from.y) * uy
            if (projection > maxProjection) maxProjection = projection
        }
        val overshootRatio = (maxProjection - length) / length
        if (overshootRatio > maxOvershootRatio) {
            throw AssertionError(
                "Overshoot ${(overshootRatio * 100).toInt()}% exceeds limit " +
                    "${(maxOvershootRatio * 100).toInt()}% for style $style"
            )
        }
        val final = samples.last().position
        val landingError = final.distanceTo(to)
        if (landingError > 1f) {
            throw AssertionError(
                "Path did not land on target (error=$landingError px) for style $style"
            )
        }
    }

    /** One sampled point of a [MotionPath]. */
    data class PathSample(
        val t: Float,
        val position: PointF,
        val progress: Float,
    )

    companion object {
        /** Convenience: asserts all built-in styles land exactly on target. */
        fun assertAllStylesLand(
            planner: MotionPlanner = MotionPlanner(),
            from: PointF = PointF(40f, 40f),
            to: PointF = PointF(860f, 640f),
            seed: Long = 42L,
        ) {
            val harness = MotionPlannerTestHarness(planner)
            for (style in MotionStyle.entries) {
                harness.assertNoOvershoot(from, to, style, seed = seed)
            }
            // Absence of AssertionError == pass.
        }
    }
}
