package com.swarmcursors.engine

import com.swarmcursors.core.geometry.PointF
import com.swarmcursors.core.geometry.RectF

/**
 * The rendering surface the engine drives (implemented by the overlay
 * controller in production and by a recorder in tests).
 */
internal interface CursorStage {

    /** Makes sure the stage can present cursors (attaches the window). */
    fun ensureReady(): Boolean

    fun onCursorShown(cursorId: Int, label: String?)

    fun onCursorMoved(cursorId: Int, x: Float, y: Float)

    fun onCursorExecuting(cursorId: Int)

    fun onCursorActionCompleted(cursorId: Int)

    /** Spring-back + fade-out visual for cancelled motions (spec §3.2). */
    fun onCursorCancelled(cursorId: Int)

    fun onCursorHidden(cursorId: Int)

    fun currentPosition(cursorId: Int): PointF?

    /** Current screen size in pixels (used for §6.3 remapping). */
    fun screenSize(): PointF

    /** Debug overlay: target node bounds (spec §8.3). */
    fun onTargetBounds(cursorId: Int, bounds: RectF?)

    fun isAttached(): Boolean
}
