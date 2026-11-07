package com.swarmcursors.windows

import com.swarmcursors.core.model.WindowTarget

/**
 * Tracks the window the agent targets (spec §6.1). `null` means "the active
 * window". PiP windows are already filtered out by the host (spec §6.2).
 */
internal class WindowRegistry {

    @Volatile
    var targetWindowId: Int? = null

    fun setTarget(windowId: Int?) {
        targetWindowId = windowId
    }

    fun resolveActive(windows: List<WindowTarget>): WindowTarget? =
        windows.firstOrNull { it.isActive }
}
