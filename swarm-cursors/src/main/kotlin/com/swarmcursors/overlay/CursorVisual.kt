package com.swarmcursors.overlay

import com.swarmcursors.theme.CursorRenderPhase

/**
 * Mutable render state of one cursor. Owned by [CursorOverlayView] and only
 * touched on the main thread.
 */
internal class CursorVisual(
    val id: Int,
    var x: Float,
    var y: Float,
) {
    enum class Phase { MOVING, EXECUTING, SPRING_BACK, FADE_OUT }

    var renderX: Float = x
    var renderY: Float = y

    var label: String? = null
    var phase: Phase = Phase.MOVING

    var alpha: Float = 1f

    var rippleStartMs: Long = 0L
    var fadeStartMs: Long = 0L
    var springStartMs: Long = 0L
    var springFromX: Float = x
    var springFromY: Float = y
    var springToX: Float = x
    var springToY: Float = y

    val trail = ArrayDeque<TrailPoint>()

    fun renderPhase(): CursorRenderPhase = when (phase) {
        Phase.MOVING -> CursorRenderPhase.MOVING
        Phase.EXECUTING -> CursorRenderPhase.EXECUTING
        Phase.SPRING_BACK -> CursorRenderPhase.SPRING_BACK
        Phase.FADE_OUT -> CursorRenderPhase.FADE_OUT
    }

    fun setPhase(phase: Phase, nowMs: Long) {
        this.phase = phase
        when (phase) {
            Phase.EXECUTING -> rippleStartMs = nowMs
            Phase.FADE_OUT -> fadeStartMs = nowMs
            Phase.SPRING_BACK -> springStartMs = nowMs
            Phase.MOVING -> Unit
        }
    }
}

internal class TrailPoint(
    val x: Float,
    val y: Float,
    val atMs: Long,
)
