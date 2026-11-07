package com.swarmcursors.core.geometry

import kotlin.math.hypot

/**
 * Minimal immutable 2D point (float) used across the library so that the
 * `:core` module stays free of Android dependencies.
 */
data class PointF(val x: Float, val y: Float) {

    fun distanceTo(other: PointF): Float = hypot(x - other.x, y - other.y)

    fun lerp(to: PointF, t: Float): PointF =
        PointF(x + (to.x - x) * t, y + (to.y - y) * t)

    companion object {
        val ZERO = PointF(0f, 0f)
    }
}

/**
 * Minimal immutable rectangle (float) mirroring the frequently used subset of
 * `android.graphics.RectF`. Coordinates are screen-global unless stated
 * otherwise.
 */
data class RectF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width * height

    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun contains(px: Float, py: Float): Boolean =
        px >= left && px <= right && py >= top && py <= bottom

    fun contains(point: PointF): Boolean = contains(point.x, point.y)

    fun intersects(other: RectF): Boolean =
        this.left <= other.right && other.left <= this.right &&
            this.top <= other.bottom && other.top <= this.bottom

    /** Center point of the rectangle. */
    fun center(): PointF = PointF(centerX, centerY)

    /**
     * Returns a copy whose coordinates are linearly remapped from [from] onto
     * [to] (used when the screen geometry changes mid-flight, e.g. rotation
     * or fold/unfold). [from] and [to] are the old/new screen bounds.
     */
    fun remap(from: RectF, to: RectF): RectF {
        if (from.width <= 0f || from.height <= 0f) return this
        val sx = to.width / from.width
        val sy = to.height / from.height
        return RectF(
            left = to.left + (left - from.left) * sx,
            top = to.top + (top - from.top) * sy,
            right = to.left + (right - from.left) * sx,
            bottom = to.top + (bottom - from.top) * sy,
        )
    }

    /** Inflates the rect by [dx]/[dy] on each side. */
    fun inflate(dx: Float, dy: Float = dx): RectF =
        RectF(left - dx, top - dy, right + dx, bottom + dy)

    companion object {
        val EMPTY = RectF(0f, 0f, 0f, 0f)

        /** Creates a rect from a center point and a size. */
        fun fromCenter(center: PointF, width: Float, height: Float): RectF =
            RectF(
                left = center.x - width / 2f,
                top = center.y - height / 2f,
                right = center.x + width / 2f,
                bottom = center.y + height / 2f,
            )

        fun bounding(points: List<PointF>): RectF {
            if (points.isEmpty()) return EMPTY
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (p in points) {
                if (p.x < l) l = p.x
                if (p.y < t) t = p.y
                if (p.x > r) r = p.x
                if (p.y > b) b = p.y
            }
            return RectF(l, t, r, b)
        }
    }
}
