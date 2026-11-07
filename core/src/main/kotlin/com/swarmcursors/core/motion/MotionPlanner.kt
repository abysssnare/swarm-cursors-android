package com.swarmcursors.core.motion

import com.swarmcursors.core.geometry.PointF
import com.swarmcursors.core.model.MotionStyle
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A planned cursor trajectory from [from] to [to].
 *
 * The geometric shape is a quadratic Bézier curve (a slight arc that makes
 * the motion look natural) parameterized by a per-style progress function
 * `p(t)` with `p(0) = 0` and `p(1) = 1`.
 */
class MotionPath(
    val from: PointF,
    val to: PointF,
    val style: MotionStyle,
    val durationMs: Long,
    val distancePx: Float,
    private val control: PointF,
) {
    /** Total number of ~60fps frames the motion is expected to take. */
    val totalFrames: Int get() = max(1, ceil(durationMs / MotionPlanner.FRAME_MS.toDouble()).toInt())

    /** Raw easing progress `p(t)` for the style, `t` in `[0f, 1f]`.
     *  Values above 1 are allowed for [MotionStyle.SPRING] (bounded overshoot). */
    fun progressAt(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        val raw = when (style) {
            MotionStyle.LINEAR -> clamped
            MotionStyle.EASE -> easeOut(clamped)
            MotionStyle.EASE_IN_OUT -> smoothStep(clamped)
            MotionStyle.SPRING -> springStep(clamped)
        }
        return raw.coerceIn(0f, 1.10f)
    }

    /** Position along the curve at normalized time `t` in `[0f, 1f]`. */
    fun positionAt(t: Float): PointF {
        val p = progressAt(t)
        val inv = 1f - p
        val x = inv * inv * from.x + 2f * inv * p * control.x + p * p * to.x
        val y = inv * inv * from.y + 2f * inv * p * control.y + p * p * to.y
        return PointF(x, y)
    }

    companion object {
        private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

        private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

        private const val SPRING_ZETA = 0.75f

        /**
         * Analytic underdamped spring normalized to end exactly at 1.
         * Damping ratio 0.75 keeps the single overshoot below ~3%.
         */
        fun springStep(t: Float): Float {
            val zeta = SPRING_ZETA
            val omega = 6.9f / zeta // settles (<0.1%) at t = 1
            val omegaD = omega * sqrt(1f - zeta * zeta)
            fun raw(u: Float): Float {
                val decay = exp(-zeta * omega * u)
                return 1f - decay * (cos(omegaD * u) + (zeta * omega / omegaD) * sin(omegaD * u))
            }
            val end = raw(1f)
            if (end == 0f) return u1(t)
            return raw(t) / end
        }

        private fun u1(t: Float): Float = t
    }
}

/**
 * Plans cursor motions (spec §9.2).
 *
 * Duration follows Fitts' law `T = a + b * log2(D / W + 1)` so farther and
 * smaller targets take proportionally longer to reach.
 */
class MotionPlanner(
    private val fittsA: Double = 120.0,
    private val fittsB: Double = 150.0,
    private val minDurationMs: Long = 180,
    private val maxDurationMs: Long = 1_400,
    private val minTargetWidthPx: Float = 24f,
    private val maxTargetWidthPx: Float = 512f,
    /** Relative curvature of the motion arc (0 = straight line). */
    private val arcRatio: Float = 0.12f,
) {

    /**
     * Plans a motion from [from] to [to].
     *
     * @param targetWidthPx effective target width used by Fitts' law.
     * @param seed seed for the arc direction; pass a constant for
     * deterministic paths (tests do this).
     */
    fun plan(
        from: PointF,
        to: PointF,
        style: MotionStyle,
        targetWidthPx: Float,
        durationHintMs: Long? = null,
        seed: Long = System.nanoTime(),
    ): MotionPath {
        val distance = from.distanceTo(to)
        val duration = durationHintMs ?: fittsDuration(distance, targetWidthPx)
        val control = arcControlPoint(from, to, distance, seed)
        return MotionPath(
            from = from,
            to = to,
            style = style,
            durationMs = duration,
            distancePx = distance,
            control = control,
        )
    }

    /** Fitts' law duration in ms, clamped to a comfortable range. */
    fun fittsDuration(distancePx: Float, targetWidthPx: Float): Long {
        if (distancePx <= 1f) return minDurationMs
        val w = targetWidthPx.coerceIn(minTargetWidthPx, maxTargetWidthPx)
        // Shannon formulation: ID = log2(D/W + 1)
        val id = log2(distancePx / w + 1.0)
        val ms = fittsA + fittsB * id
        return ms.toLong().coerceIn(minDurationMs, maxDurationMs)
    }

    private fun arcControlPoint(from: PointF, to: PointF, distance: Float, seed: Long): PointF {
        if (distance <= 1f || arcRatio <= 0f) return from.lerp(to, 0.5f)
        val random = Random(seed)
        val magnitude = distance * arcRatio * (0.4f + random.nextFloat() * 0.6f)
        val sign = if (random.nextBoolean()) 1f else -1f
        // Perpendicular of (to - from), normalized.
        val dx = (to.x - from.x) / distance
        val dy = (to.y - from.y) / distance
        val mid = from.lerp(to, 0.5f)
        return PointF(mid.x + (-dy) * magnitude * sign, mid.y + dx * magnitude * sign)
    }

    companion object {
        /** Target frame period used by the engine's motion loop (~60fps). */
        const val FRAME_MS = 16L

        /** Natural-log-free angular helper kept for readability in tests. */
        const val TAU: Float = (2.0 * PI).toFloat()
    }
}
