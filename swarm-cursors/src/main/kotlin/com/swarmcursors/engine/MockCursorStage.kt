package com.swarmcursors.engine

import com.swarmcursors.core.geometry.PointF
import com.swarmcursors.core.geometry.RectF
import com.swarmcursors.test.MockAccessibilityHost

/**
 * Records cursor positions instead of rendering; used whenever the mock
 * service is installed (spec §9.1).
 */
internal class MockCursorStage : CursorStage {

    private val positions = HashMap<Int, MutableList<PointF>>()
    private val shown = mutableListOf<Int>()
    private val executedPulses = mutableListOf<Int>()

    override fun ensureReady(): Boolean = true

    override fun onCursorShown(cursorId: Int, label: String?) {
        shown.add(cursorId)
    }

    override fun onCursorMoved(cursorId: Int, x: Float, y: Float) {
        positions.getOrPut(cursorId) { mutableListOf() }.add(PointF(x, y))
    }

    override fun onCursorExecuting(cursorId: Int) {
        executedPulses.add(cursorId)
    }

    override fun onCursorActionCompleted(cursorId: Int) = Unit

    override fun onCursorCancelled(cursorId: Int) = Unit

    override fun onCursorHidden(cursorId: Int) = Unit

    override fun currentPosition(cursorId: Int): PointF? =
        positions[cursorId]?.lastOrNull()

    override fun screenSize(): PointF = MockAccessibilityHost.MOCK_SCREEN

    override fun onTargetBounds(cursorId: Int, bounds: RectF?) = Unit

    override fun isAttached(): Boolean = true

    fun positionsFor(cursorId: Int): List<PointF> =
        positions[cursorId]?.toList() ?: emptyList()

    fun shownCursors(): List<Int> = shown.toList()

    fun executionPulses(): List<Int> = executedPulses.toList()
}
