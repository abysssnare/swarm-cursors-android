package com.swarmcursors.core.motion

import com.swarmcursors.core.geometry.PointF
import com.swarmcursors.core.model.MotionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CI verification of the motion engine contract (spec §9.2):
 * interpolation continuity, Fitts monotonicity, spring overshoot < 10%.
 */
class MotionPlannerTest {

    private val planner = MotionPlanner()
    private val harness = MotionPlannerTestHarness(planner)

    @Test
    fun `adjacent samples stay within continuity budget for every style`() {
        val from = PointF(40f, 40f)
        val to = PointF(860f, 640f)
        val distance = from.distanceTo(to) // ~ 1,016 px
        for (style in MotionStyle.entries) {
            // 50 samples over the path; SPRING's peak slope is ~4× distance
            // per unit t, so the budget must accommodate 4 × 2% ≈ 8-10% of
            // the total distance per step.
            harness.assertInterpolationContinuity(
                from = from,
                to = to,
                style = style,
                maxStepDistancePx = distance * 0.11f,
                seed = 42L,
            )
        }
    }

    @Test
    fun `fitts duration grows monotonically with distance`() {
        for (style in MotionStyle.entries) {
            harness.assertDurationMonotonic(style = style)
        }
    }

    @Test
    fun `spring lands without visible oscillation`() {
        harness.assertNoOvershoot(
            from = PointF(100f, 100f),
            to = PointF(700f, 500f),
            style = MotionStyle.SPRING,
            maxOvershootRatio = 0.10f,
            seed = 7L,
        )
    }

    @Test
    fun `all styles land exactly on target`() {
        MotionPlannerTestHarness.assertAllStylesLand(seed = 11L)
    }

    @Test
    fun `spring overshoot stays below ten percent`() {
        val from = PointF(0f, 0f)
        val to = PointF(600f, 0f)
        val path = planner.plan(from, to, MotionStyle.SPRING, targetWidthPx = 48f, seed = 3L)
        var maxProgress = 0f
        for (i in 0..400) {
            val t = i / 400f
            maxProgress = maxOf(maxProgress, path.progressAt(t))
        }
        assertTrue("Overshoot ${maxProgress} exceeded 1.10", maxProgress <= 1.10f)
        assertTrue("Spring showed no settle", maxProgress > 1.0f)
        assertEquals(1f, path.progressAt(1f), 0.001f)
    }

    @Test
    fun `duration hint overrides fitts estimate`() {
        val path = planner.plan(
            PointF(0f, 0f),
            PointF(500f, 0f),
            MotionStyle.LINEAR,
            targetWidthPx = 48f,
            durationHintMs = 777L,
            seed = 1L,
        )
        assertEquals(777L, path.durationMs)
    }

    @Test
    fun `short hops use the minimum duration`() {
        val path = planner.plan(
            PointF(0f, 0f),
            PointF(0.5f, 0.5f),
            MotionStyle.EASE,
            targetWidthPx = 48f,
            seed = 1L,
        )
        assertEquals(planner.fittsDuration(0.7f, 48f), path.durationMs)
    }

    @Test
    fun `tiny distances never divide by zero`() {
        val path = planner.plan(
            PointF(10f, 10f),
            PointF(10f, 10f),
            MotionStyle.SPRING,
            targetWidthPx = 24f,
            seed = 1L,
        )
        assertEquals(PointF(10f, 10f), path.positionAt(1f))
    }
}
