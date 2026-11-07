package com.swarmcursors.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import com.swarmcursors.core.geometry.PointF
import com.swarmcursors.core.geometry.RectF
import com.swarmcursors.core.log.SwarmLog
import com.swarmcursors.core.metrics.MetricsCollector
import com.swarmcursors.core.model.SwarmConfig
import com.swarmcursors.engine.CursorStage

/**
 * Owns the overlay window lifecycle (spec §2.2/§6.2). The window is created
 * lazily on first use (first `injectActions` or `setCursorVisible(true)`)
 * once the overlay permission is granted, and removed when the service is
 * unbound. Split-screen/PiP: the overlay covers the whole physical screen and
 * accessibility bounds are already screen-global, so no coordinate
 * transformation is needed.
 */
internal class OverlayController(
    private val context: Context,
    private val configProvider: () -> SwarmConfig,
    private val metrics: MetricsCollector,
) : CursorStage {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: CursorOverlayView? = null
    private var pendingDebugEnabled = false

    private val windowManager: WindowManager
        get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** Adds the overlay window (idempotent). Must run on the main thread. */
    fun attach(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            SwarmLog.w("OverlayController.attach called off the main thread — posting")
            mainHandler.post { attach() }
            return false
        }
        if (view != null) return true
        if (!Settings.canDrawOverlays(context)) {
            SwarmLog.w("SYSTEM_ALERT_WINDOW permission not granted — cursor overlay disabled")
            return false
        }
        val overlayView = CursorOverlayView(context, configProvider, metrics)
        overlayView.setDebugEnabled(pendingDebugEnabled)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        return try {
            windowManager.addView(overlayView, params)
            view = overlayView
            SwarmLog.i("Overlay window attached")
            true
        } catch (t: Throwable) {
            SwarmLog.e("Failed to attach overlay window", t)
            false
        }
    }

    /** Removes the overlay window and frees its surface (spec §2.2). */
    fun detach() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { detach() }
            return
        }
        val current = view ?: return
        view = null
        runCatching { windowManager.removeView(current) }
        SwarmLog.i("Overlay window detached")
    }

    /** Spec §7.1: fields that cannot be hot-swapped force a rebuild. */
    fun rebuild() {
        detach()
        attach()
    }

    fun setDebugEnabled(enabled: Boolean) {
        pendingDebugEnabled = enabled
        view?.setDebugEnabled(enabled)
    }

    fun pauseRendering() {
        view?.pauseRendering()
    }

    fun resumeRendering() {
        view?.resumeRendering()
    }

    /** Spec §6.3: display geometry changed (rotation/fold). */
    fun onDisplayGeometryChanged() {
        val current = view ?: return
        // The window is MATCH_PARENT, so the system relayouts it; just make
        // sure the frame loop keeps running and stale state is dropped.
        current.post {
            // Nothing else required: view size changes propagate automatically.
            SwarmLog.d("Overlay display geometry changed")
        }
    }

    // ---- CursorStage ------------------------------------------------------

    override fun ensureReady(): Boolean {
        // Attach lazily; actions still run without the overlay (permission
        // may be missing), they are just not visualized.
        attach()
        return true
    }

    override fun onCursorShown(cursorId: Int, label: String?) {
        view?.showCursor(cursorId, label)
    }

    override fun onCursorMoved(cursorId: Int, x: Float, y: Float) {
        view?.moveCursor(cursorId, x, y)
    }

    override fun onCursorExecuting(cursorId: Int) {
        view?.cursorExecuting(cursorId)
    }

    override fun onCursorActionCompleted(cursorId: Int) {
        view?.cursorActionCompleted(cursorId)
    }

    override fun onCursorCancelled(cursorId: Int) {
        view?.cursorCancelled(cursorId)
    }

    override fun onCursorHidden(cursorId: Int) {
        view?.hideCursor(cursorId)
    }

    override fun currentPosition(cursorId: Int): PointF? =
        view?.currentPosition(cursorId)

    override fun screenSize(): PointF {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                PointF(bounds.width().toFloat(), bounds.height().toFloat())
            } else {
                @Suppress("DEPRECATION")
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.getRealMetrics(metrics)
                PointF(
                    metrics.widthPixels.toFloat(),
                    metrics.heightPixels.toFloat(),
                )
            }
        } catch (t: Throwable) {
            SwarmLog.w("screenSize() failed, falling back to resources: ${t.message}")
            val dm = context.resources.displayMetrics
            PointF(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        }
    }

    override fun onTargetBounds(cursorId: Int, bounds: RectF?) {
        view?.setTargetBounds(cursorId, bounds)
    }

    override fun isAttached(): Boolean = view != null

    /** Current configuration snapshot (for tests/diagnostics). */
    fun currentConfig(): SwarmConfig = configProvider()
}
