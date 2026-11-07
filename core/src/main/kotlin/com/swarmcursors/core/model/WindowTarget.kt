package com.swarmcursors.core.model

import com.swarmcursors.core.geometry.RectF

/**
 * Description of a window the agent may target (spec §6.1).
 */
data class WindowTarget(
    val windowId: Int,
    val title: String?,
    val bounds: RectF,
    val isActive: Boolean,
    val isPip: Boolean = false,
)
