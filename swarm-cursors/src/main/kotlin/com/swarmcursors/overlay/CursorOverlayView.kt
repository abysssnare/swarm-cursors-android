package com.swarmcursors.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import com.swarmcursors.core.geometry.PointF
import com.swarmcursors.core.geometry.RectF
import com.swarmcursors.core.metrics.MetricsCollector
import com.swarmcursors.core.model.CursorAppearance
import com.swarmcursors.core.model.SwarmConfig
import com.swarmcursors.core.log.SwarmLog
import com.swarmcursors.theme.CursorThemes
import com.swarmcursors.theme.DefaultCursorTheme
import kotlin.math.sqrt

/**
 * The full-screen, non-touchable overlay window that renders agent cursors,
 * trails, ripples, action labels and (optionally) debug information
 * (spec §2.2/§7/§8.3).
 *
 * Rendering uses a Choreographer frame loop; the window surface itself is
 * managed by the [OverlayController]. All methods must be called on the main
 * thread.
 */
internal class CursorOverlayView(
    context: Context,
    private val configProvider: () -> SwarmConfig,
    private val metrics: MetricsCollector,
) : View(context) {

    private val density = resources.displayMetrics.density

    private val cursors = HashMap<Int, CursorVisual>()
    private val targetBounds = HashMap<Int, RectF>()

    @Volatile
    private var debugEnabled = false

    private var paused = false
    private var loopActive = false
    private var lastFrameNanos = 0L
    private var fps = 0f

    // ---- Paints -----------------------------------------------------------

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.5f * density
        isFakeBoldText = true
    }
    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        isFakeBoldText = true
    }
    private val debugRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFFF5252.toInt()
        pathEffect = DashPathEffect(floatArrayOf(9f, 7f), 0f)
    }
    private val debugBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x99000000.toInt()
    }

    // ---- Frame loop (spec §2.2: paused/resumed, surface freed on detach) --

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) {
                loopActive = false
                lastFrameNanos = 0L
                return
            }
            if (lastFrameNanos != 0L) {
                val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f
                if (deltaMs > 0f && deltaMs < 500f) {
                    fps = if (fps == 0f) 1000f / deltaMs else 0.9f * fps + 0.1f * (1000f / deltaMs)
                    metrics.recordFrame(deltaMs)
                }
            }
            lastFrameNanos = frameTimeNanos
            updateAnimations(SystemClock.elapsedRealtime())
            invalidate()
            if (paused) {
                loopActive = false
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private fun ensureFrameLoop() {
        if (loopActive || paused || !isAttachedToWindow) return
        loopActive = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureFrameLoop()
    }

    override fun onDetachedFromWindow() {
        loopActive = false
        lastFrameNanos = 0L
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        // §6.3: geometry change — drop stale trails so the cursor does not
        // smear across the rotation.
        for (cursor in cursors.values) cursor.trail.clear()
        lastFrameNanos = 0L
        ensureFrameLoop()
    }

    fun pauseRendering() {
        paused = true
        SwarmLog.i("Overlay rendering paused")
    }

    fun resumeRendering() {
        paused = false
        lastFrameNanos = 0L
        ensureFrameLoop()
        SwarmLog.i("Overlay rendering resumed")
    }

    // ---- Stage API (engine calls, main thread) ----------------------------

    fun showCursor(cursorId: Int, label: String?) {
        val cursor = obtain(cursorId)
        cursor.label = label
        cursor.alpha = 1f
        cursor.setPhase(CursorVisual.Phase.MOVING, SystemClock.elapsedRealtime())
        ensureFrameLoop()
    }

    fun moveCursor(cursorId: Int, x: Float, y: Float) {
        val cursor = obtain(cursorId)
        cursor.x = x
        cursor.y = y
        ensureFrameLoop()
    }

    fun cursorExecuting(cursorId: Int) {
        val cursor = obtain(cursorId)
        cursor.setPhase(CursorVisual.Phase.EXECUTING, SystemClock.elapsedRealtime())
        ensureFrameLoop()
    }

    fun cursorActionCompleted(cursorId: Int) {
        val cursor = cursors[cursorId] ?: return
        cursor.setPhase(CursorVisual.Phase.FADE_OUT, SystemClock.elapsedRealtime())
        ensureFrameLoop()
    }

    fun cursorCancelled(cursorId: Int) {
        val cursor = cursors[cursorId] ?: return
        // Spec §3.2: quick spring-back, then fade out.
        val dx = cursor.x - cursor.renderX
        val dy = cursor.y - cursor.renderY
        val length = sqrt(dx * dx + dy * dy)
        if (length > 1f) {
            cursor.springFromX = cursor.renderX
            cursor.springFromY = cursor.renderY
            cursor.springToX = cursor.renderX - (dx / length) * SPRING_BACK_PX
            cursor.springToY = cursor.renderY - (dy / length) * SPRING_BACK_PX
        } else {
            cursor.springFromX = cursor.renderX
            cursor.springFromY = cursor.renderY
            cursor.springToX = cursor.renderX
            cursor.springToY = cursor.renderY
        }
        cursor.setPhase(CursorVisual.Phase.SPRING_BACK, SystemClock.elapsedRealtime())
        ensureFrameLoop()
    }

    fun hideCursor(cursorId: Int) {
        val cursor = cursors[cursorId] ?: return
        if (cursor.phase == CursorVisual.Phase.FADE_OUT) return
        cursor.setPhase(CursorVisual.Phase.FADE_OUT, SystemClock.elapsedRealtime())
    }

    fun currentPosition(cursorId: Int): PointF? =
        cursors[cursorId]?.let { PointF(it.x, it.y) }

    fun setTargetBounds(cursorId: Int, bounds: RectF?) {
        if (bounds == null) targetBounds.remove(cursorId) else targetBounds[cursorId] = bounds
    }

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
        if (enabled) ensureFrameLoop()
    }

    fun hasVisibleCursors(): Boolean = cursors.isNotEmpty()

    // ---- Animation ---------------------------------------------------------

    private fun updateAnimations(nowMs: Long) {
        val config = configProvider()
        val expired = ArrayList<Int>()
        for (cursor in cursors.values) {
            // Exponential smoothing towards the engine-reported position.
            val smoothing = 0.55f
            cursor.renderX += (cursor.x - cursor.renderX) * smoothing
            cursor.renderY += (cursor.y - cursor.renderY) * smoothing

            when (cursor.phase) {
                CursorVisual.Phase.MOVING -> {
                    if (config.enableTrail) {
                        cursor.trail.addLast(TrailPoint(cursor.renderX, cursor.renderY, nowMs))
                        while (cursor.trail.size > TRAIL_POINTS) cursor.trail.removeFirst()
                    } else if (cursor.trail.isNotEmpty()) {
                        cursor.trail.clear()
                    }
                }
                CursorVisual.Phase.EXECUTING -> {
                    if (nowMs - cursor.rippleStartMs > RIPPLE_DURATION_MS) {
                        cursor.trail.clear()
                    }
                }
                CursorVisual.Phase.SPRING_BACK -> {
                    cursor.trail.clear()
                    val t = ((nowMs - cursor.springStartMs).toFloat() / SPRING_BACK_DURATION_MS)
                        .coerceIn(0f, 1f)
                    cursor.renderX = lerp(cursor.springFromX, cursor.springToX, t)
                    cursor.renderY = lerp(cursor.springFromY, cursor.springToY, t)
                    if (t >= 1f) {
                        cursor.setPhase(CursorVisual.Phase.FADE_OUT, nowMs)
                    }
                }
                CursorVisual.Phase.FADE_OUT -> {
                    cursor.trail.clear()
                    val t = ((nowMs - cursor.fadeStartMs).toFloat() / FADE_DURATION_MS)
                        .coerceIn(0f, 1f)
                    cursor.alpha = 1f - t
                    if (t >= 1f) expired.add(cursor.id)
                }
            }
            // Drop stale trail points regardless of phase.
            while (cursor.trail.isNotEmpty() && nowMs - cursor.trail.first().atMs > TRAIL_TTL_MS) {
                cursor.trail.removeFirst()
            }
        }
        for (id in expired) cursors.remove(id)
        if (cursors.isEmpty()) targetBounds.clear()
    }

    // ---- Drawing -----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val config = configProvider()
        val appearance = config.appearance
        val theme = CursorThemes.get(config.theme) ?: CursorThemes.defaultRenderer()
        val now = SystemClock.elapsedRealtime()

        for (cursor in cursors.values.toList()) {
            if (config.enableTrail) drawTrail(canvas, cursor, appearance, now)
            if (cursor.phase == CursorVisual.Phase.EXECUTING) {
                drawRipple(canvas, cursor, appearance, now)
            }
            theme.drawCursor(
                canvas = canvas,
                phase = cursor.renderPhase(),
                x = cursor.renderX,
                y = cursor.renderY,
                alpha = cursor.alpha,
                appearance = appearance,
                scale = config.cursorScale,
                density = density,
            )
            if (config.enableActionLabel && cursor.label != null && cursor.alpha > 0.3f) {
                drawLabel(canvas, cursor, appearance)
            }
            if (debugEnabled) drawCursorDebug(canvas, cursor)
        }

        if (debugEnabled) drawDebugHud(canvas)
    }

    private fun drawTrail(
        canvas: Canvas,
        cursor: CursorVisual,
        appearance: CursorAppearance,
        now: Long,
    ) {
        for (point in cursor.trail) {
            val age = ((now - point.atMs).toFloat() / TRAIL_TTL_MS).coerceIn(0f, 1f)
            val radius = appearance.dotRadius * density * (1f - age) * 0.75f * cursor.alpha
            if (radius <= 0.5f) continue
            trailPaint.color = DefaultCursorTheme.scaleAlpha(appearance.rippleColor, (1f - age) * 0.7f * cursor.alpha)
            canvas.drawCircle(point.x, point.y, radius, trailPaint)
        }
    }

    private fun drawRipple(
        canvas: Canvas,
        cursor: CursorVisual,
        appearance: CursorAppearance,
        now: Long,
    ) {
        val t = ((now - cursor.rippleStartMs).toFloat() / RIPPLE_DURATION_MS).coerceIn(0f, 1f)
        val radius = lerp(appearance.dotRadius * density, RIPPLE_MAX_RADIUS_PX * density, t)
        ripplePaint.color = DefaultCursorTheme.scaleAlpha(appearance.rippleColor, (1f - t) * cursor.alpha)
        ripplePaint.strokeWidth = 2.5f * density * (1f - t) + 0.5f
        canvas.drawCircle(cursor.x, cursor.y, radius, ripplePaint)
    }

    private fun drawLabel(
        canvas: Canvas,
        cursor: CursorVisual,
        appearance: CursorAppearance,
    ) {
        val text = cursor.label ?: return
        val textWidth = labelTextPaint.measureText(text)
        val paddingH = 8f * density
        val paddingV = 5f * density
        val left = cursor.renderX + 14f * density
        val top = cursor.renderY - 4f * density
        val right = left + textWidth + paddingH * 2f
        val bottom = top + labelTextPaint.textSize + paddingV * 2f

        labelBgPaint.color = DefaultCursorTheme.scaleAlpha(appearance.labelBackgroundColor, cursor.alpha)
        labelTextPaint.color = DefaultCursorTheme.scaleAlpha(appearance.labelTextColor, cursor.alpha)

        canvas.drawRoundRect(left, top, right, bottom, 6f * density, 6f * density, labelBgPaint)
        canvas.drawText(text, left + paddingH, bottom - paddingV, labelTextPaint)
    }

    private fun drawCursorDebug(canvas: Canvas, cursor: CursorVisual) {
        debugTextPaint.color = 0xFF00E676.toInt()
        val text = "c${cursor.id} ${cursor.phase.name.lowercase()}"
        canvas.drawText(text, cursor.renderX + 14f * density, cursor.renderY + 22f * density, debugTextPaint)
    }

    /** Spec §8.3: cursor ids, target bounds, FPS and current phase. */
    private fun drawDebugHud(canvas: Canvas) {
        val hud = buildString {
            append("SwarmCursors debug  FPS: ")
            append(fps.toInt())
            append("  cursors: ")
            append(cursors.size)
            append("\n")
            for (cursor in cursors.values) {
                append("c${cursor.id}: (${cursor.x.toInt()},${cursor.y.toInt()}) ${cursor.phase.name.lowercase()}")
                append("  ")
            }
        }
        val padding = 12f * density
        val lineCount = 1 + cursors.size
        val textHeight = debugTextPaint.textSize * lineCount * 1.35f + padding * 2f
        var maxLineWidth = 0f
        for (line in hud.lineSequence()) {
            maxLineWidth = maxOf(maxLineWidth, debugTextPaint.measureText(line))
        }
        canvas.drawRect(0f, 0f, maxLineWidth + padding * 2f, textHeight, debugBgPaint)
        debugTextPaint.color = 0xFF00E676.toInt()
        var y = padding + debugTextPaint.textSize
        for (line in hud.lineSequence()) {
            canvas.drawText(line, padding, y, debugTextPaint)
            y += debugTextPaint.textSize * 1.35f
        }
        // Red dashed target bounds (spec §8.3).
        for (bounds in targetBounds.values) {
            canvas.drawRect(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                debugRectPaint,
            )
        }
    }

    private fun obtain(cursorId: Int): CursorVisual =
        cursors.getOrPut(cursorId) {
            val spawn = PointF(width * 0.25f, height * 0.3f)
            CursorVisual(cursorId, spawn.x, spawn.y).apply {
                renderX = x
                renderY = y
            }
        }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    companion object {
        private const val TRAIL_TTL_MS = 320L
        private const val TRAIL_POINTS = 18
        private const val RIPPLE_DURATION_MS = 450L
        private const val RIPPLE_MAX_RADIUS_PX = 42f
        private const val FADE_DURATION_MS = 300L
        private const val SPRING_BACK_PX = 36f
        private const val SPRING_BACK_DURATION_MS = 240L
    }
}
