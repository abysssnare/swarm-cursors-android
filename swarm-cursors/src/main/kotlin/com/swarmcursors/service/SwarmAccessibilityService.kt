package com.swarmcursors.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.swarmcursors.SwarmCursors
import com.swarmcursors.core.log.SwarmLog
import java.lang.ref.WeakReference

/**
 * The accessibility service powering Swarm Cursors (spec §2.1).
 *
 * Integrators do not interact with this class directly; everything goes
 * through [SwarmCursors]. The service keeps a static weak reference to its
 * connected instance so the library (and only the library) can reach it.
 */
class SwarmAccessibilityService : AccessibilityService() {

    companion object {
        @JvmStatic
        fun getInstance(): SwarmAccessibilityService? = instance?.get()

        internal var instance: WeakReference<SwarmAccessibilityService>? = null
    }

    private var displayListener: DisplayManager.DisplayListener? = null
    private var paused: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        SwarmCursors.onServiceConnectedInternal()
        registerDisplayListener()
        SwarmLog.i("SwarmAccessibilityService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        SwarmLog.i("SwarmAccessibilityService unbound")
        instance = null
        SwarmCursors.onServiceUnboundInternal()
        unregisterDisplayListener()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        SwarmLog.i("SwarmAccessibilityService destroyed")
        instance = null
        SwarmCursors.onServiceUnboundInternal()
        unregisterDisplayListener()
        super.onDestroy()
    }

    /**
     * Spec §2.2: pause rendering, keep state so it can resume later.
     */
    override fun onInterrupt() {
        paused = true
        SwarmCursors.onOverlayPauseInternal()
        SwarmLog.i("SwarmAccessibilityService interrupted — rendering paused")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (paused) {
            paused = false
            SwarmCursors.onOverlayResumeInternal()
        }
        SwarmLog.v("a11y event type=${event?.eventType}")
    }

    /**
     * Spec §6.3: display geometry changes (rotation, fold/unfold, split
     * screen) are forwarded to the overlay so it can rebuild its surface.
     */
    private fun registerDisplayListener() {
        val manager = getSystemService(DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                SwarmCursors.onDisplayGeometryChangedInternal()
            }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        displayListener = listener
    }

    private fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        val manager = getSystemService(DISPLAY_SERVICE) as? DisplayManager ?: return
        manager.unregisterDisplayListener(listener)
        displayListener = null
    }
}
