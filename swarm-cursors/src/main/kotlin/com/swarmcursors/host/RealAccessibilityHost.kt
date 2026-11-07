package com.swarmcursors.host

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.swarmcursors.core.geometry.RectF
import com.swarmcursors.core.log.SwarmLog
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.NodeSummary
import com.swarmcursors.core.model.ScrollDirection
import com.swarmcursors.core.model.WindowTarget
import com.swarmcursors.service.SwarmAccessibilityService

/**
 * Production [AccessibilityHost] backed by the connected
 * [SwarmAccessibilityService].
 */
internal class RealAccessibilityHost : AccessibilityHost {

    private val service: SwarmAccessibilityService?
        get() = SwarmAccessibilityService.getInstance()

    override fun isReady(): Boolean = service != null

    override fun windows(): List<WindowTarget> {
        val s = service ?: return emptyList()
        val raw = s.windows ?: return emptyList()
        return raw.mapNotNull { window ->
            // Spec §6.2: picture-in-picture windows never become targets.
            // (AccessibilityWindowInfo.TYPE_PIP == 7; the symbolic constant
            // is not visible on every compile SDK stub, hence the local one.)
            if (window.type == WINDOW_TYPE_PIP) return@mapNotNull null
            val rect = Rect()
            window.getBoundsInScreen(rect)
            WindowTarget(
                windowId = window.id,
                title = window.title?.toString(),
                bounds = RectF(
                    left = rect.left.toFloat(),
                    top = rect.top.toFloat(),
                    right = rect.right.toFloat(),
                    bottom = rect.bottom.toFloat(),
                ),
                isActive = window.isActive,
                isPip = false,
            )
        }
    }

    override fun rootInWindow(windowId: Int?): NodeHandle? {
        val s = service ?: return null
        val root = if (windowId == null) {
            s.rootInActiveWindow
        } else {
            val windows = s.windows ?: emptyList()
            windows.firstOrNull { it.id == windowId }?.root ?: s.rootInActiveWindow
        }
        return root?.let { RealNodeHandle(it) }
    }

    override fun performGlobalAction(action: GlobalAction): Boolean =
        service?.performGlobalAction(action.code) ?: false
    override fun injectTap(x: Float, y: Float): Boolean {
        val s = service ?: return false
        return try {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 48L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            s.dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            SwarmLog.e("dispatchGesture failed for tap at ($x, $y)", t)
            false
        }
    }

    companion object {
        /** Mirrors the hidden-in-some-stubs `AccessibilityWindowInfo.TYPE_PIP`. */
        private const val WINDOW_TYPE_PIP = 7
    }
}

/**
 * Wraps an [AccessibilityNodeInfo]; all calls are cheap, binder-backed reads.
 */
internal class RealNodeHandle(private val info: AccessibilityNodeInfo) : NodeHandle {

    override fun isVisibleToUser(): Boolean = info.isVisibleToUser

    override fun isEnabled(): Boolean = info.isEnabled

    override fun isPassword(): Boolean = info.isPassword

    override fun boundsInScreen(): RectF {
        val rect = Rect()
        info.getBoundsInScreen(rect)
        return RectF(
            left = rect.left.toFloat(),
            top = rect.top.toFloat(),
            right = rect.right.toFloat(),
            bottom = rect.bottom.toFloat(),
        )
    }

    override fun childCount(): Int = info.childCount

    override fun child(index: Int): NodeHandle? {
        if (index < 0 || index >= info.childCount) return null
        return info.getChild(index)?.let { RealNodeHandle(it) }
    }

    override fun refresh(): Boolean = try {
        info.refresh()
    } catch (t: Throwable) {
        SwarmLog.d("Node refresh failed: ${t.message}")
        false
    }

    override fun performAction(action: AgentAction): Boolean = try {
        when (action.type) {
            com.swarmcursors.core.model.ActionType.CLICK ->
                info.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            com.swarmcursors.core.model.ActionType.LONG_CLICK ->
                info.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

            com.swarmcursors.core.model.ActionType.TYPE -> {
                val arguments = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        action.text,
                    )
                }
                info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }

            com.swarmcursors.core.model.ActionType.SCROLL -> performScroll(action.direction)

            com.swarmcursors.core.model.ActionType.SELECT ->
                info.performAction(AccessibilityNodeInfo.ACTION_SELECT)

            com.swarmcursors.core.model.ActionType.CLEAR_SELECTION ->
                info.performAction(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION)

            // Global actions are executed via AccessibilityHost.performGlobalAction.
            com.swarmcursors.core.model.ActionType.BACK,
            com.swarmcursors.core.model.ActionType.HOME -> false
        }
    } catch (t: Throwable) {
        SwarmLog.e("performAction(${action.type}) threw", t)
        false
    }

    private fun performScroll(direction: ScrollDirection): Boolean {
        // Directional scroll actions live on AccessibilityAction (API 23+),
        // not as int constants on AccessibilityNodeInfo.
        val directional = when (direction) {
            ScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            ScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            ScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            ScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (info.performAction(directional.id)) return true
        // Fallback for views that only support forward/backward scrolling.
        val fallback = when (direction) {
            ScrollDirection.DOWN, ScrollDirection.RIGHT ->
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD

            else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return info.performAction(fallback)
    }

    override fun toSummary(nodeId: String, parentId: String?, depth: Int): NodeSummary {
        val rect = Rect()
        info.getBoundsInScreen(rect)
        return NodeSummary(
            nodeId = nodeId,
            parentId = parentId,
            text = info.text?.toString(),
            contentDescription = info.contentDescription?.toString(),
            className = info.className?.toString(),
            viewIdResourceName = info.viewIdResourceName,
            bounds = RectF(
                left = rect.left.toFloat(),
                top = rect.top.toFloat(),
                right = rect.right.toFloat(),
                bottom = rect.bottom.toFloat(),
            ),
            isClickable = info.isClickable,
            isEnabled = info.isEnabled,
            isVisibleToUser = info.isVisibleToUser,
            isScrollable = info.isScrollable,
            isPassword = info.isPassword,
            isCheckable = info.isCheckable,
            isChecked = info.isChecked,
            isEditable = info.isEditable,
            inputType = info.inputType,
            depth = depth,
            windowId = info.windowId,
        )
    }
}
