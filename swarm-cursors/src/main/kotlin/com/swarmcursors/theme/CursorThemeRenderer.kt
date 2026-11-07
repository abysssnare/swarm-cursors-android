package com.swarmcursors.theme

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.swarmcursors.core.model.CursorAppearance

/** Visual phase of a cursor, forwarded to theme renderers. */
enum class CursorRenderPhase { MOVING, EXECUTING, SPRING_BACK, FADE_OUT }

/**
 * Draws a single cursor (spec §7.2). Implementations must be stateless with
 * respect to cursors — all state is passed in — so one renderer instance can
 * serve every cursor.
 */
interface CursorThemeRenderer {

    val name: String

    /**
     * Draws the cursor glyph.
     *
     * @param x render x position (screen px)
     * @param y render y position (screen px)
     * @param alpha 0..1 overall alpha
     * @param scale config `cursorScale`
     * @param density display density multiplier
     */
    fun drawCursor(
        canvas: Canvas,
        phase: CursorRenderPhase,
        x: Float,
        y: Float,
        alpha: Float,
        appearance: CursorAppearance,
        scale: Float,
        density: Float,
    )
}

/**
 * Theme registry (spec §7.2). Integrators can register custom renderers;
 * `SwarmConfig.theme` selects one by name (hot-updatable, spec §7.1).
 */
object CursorThemes {

    const val DEFAULT = "default"

    private val factories = java.util.concurrent.ConcurrentHashMap<String, () -> CursorThemeRenderer>()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CursorThemeRenderer>()

    init {
        registerBuiltIn()
    }

    fun register(name: String, factory: () -> CursorThemeRenderer) {
        require(name.isNotBlank()) { "theme name must not be blank" }
        factories[name] = factory
        cache.remove(name)
    }

    fun get(name: String): CursorThemeRenderer? {
        cache[name]?.let { return it }
        val factory = factories[name] ?: return null
        val renderer = factory()
        cache[name] = renderer
        return renderer
    }

    fun defaultRenderer(): CursorThemeRenderer {
        val renderer = get(DEFAULT)
        checkNotNull(renderer) { "Built-in '$DEFAULT' theme missing" }
        return renderer
    }

    fun unregister(name: String) {
        if (name == DEFAULT) return
        factories.remove(name)
        cache.remove(name)
    }

    fun availableThemes(): List<String> = factories.keys().toList().sorted()

    private fun registerBuiltIn() {
        factories[DEFAULT] = { DefaultCursorTheme() }
    }
}

/**
 * Classic arrow-pointer cursor with a contrasting stroke (spec §7.3 colors).
 */
class DefaultCursorTheme : CursorThemeRenderer {

    override val name: String = CursorThemes.DEFAULT

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }

    override fun drawCursor(
        canvas: Canvas,
        phase: CursorRenderPhase,
        x: Float,
        y: Float,
        alpha: Float,
        appearance: CursorAppearance,
        scale: Float,
        density: Float,
    ) {
        val size = BASE_SIZE_PX * density * scale.coerceIn(0.25f, 4f)
        val path = arrowPath(size)

        fillPaint.color = withAlpha(appearance.arrowColor, alpha)
        strokePaint.color = withAlpha(appearance.arrowStrokeColor, alpha)
        strokePaint.strokeWidth = 1.4f * density

        canvas.save()
        canvas.translate(x, y)
        if (phase == CursorRenderPhase.SPRING_BACK) {
            // Slight tilt gives the spring-back a physical feel.
            canvas.rotate(-8f)
        }
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
        canvas.restore()
    }

    private fun arrowPath(size: Float): Path = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, size * 1.35f)
        lineTo(size * 0.32f, size * 1.02f)
        lineTo(size * 0.55f, size * 1.50f)
        lineTo(size * 0.78f, size * 1.38f)
        lineTo(size * 0.55f, size * 0.92f)
        lineTo(size * 0.95f, size * 0.90f)
        close()
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = ((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    companion object {
        /** Base arrow size in dp-ish units, scaled by density. */
        const val BASE_SIZE_PX = 18f

        /** Utility used by the overlay for label/ripple colors. */
        fun scaleAlpha(color: Int, alpha: Float): Int {
            val a = ((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
            return (color and 0x00FFFFFF) or (a shl 24)
        }
    }
}
