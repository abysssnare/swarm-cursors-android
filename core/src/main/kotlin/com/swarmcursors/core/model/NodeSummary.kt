package com.swarmcursors.core.model

import com.swarmcursors.core.geometry.RectF

/**
 * Android [android.text.InputType] constants relevant to sanitization.
 * Redefined here so `:core` has no Android dependency.
 */
object InputTypes {
    const val MASK_CLASS = 0x0000000f
    const val CLASS_TEXT = 0x00000001

    const val MASK_VARIATION = 0x00000ff0
    const val VARIATION_PASSWORD = 0x00000080
    const val VARIATION_VISIBLE_PASSWORD = 0x00000090
    const val VARIATION_WEB_PASSWORD = 0x000000e0

    /**
     * True when the input type denotes a password-like field whose content
     * must never be captured.
     */
    fun isPasswordInputType(inputType: Int): Boolean {
        if (inputType and MASK_CLASS != CLASS_TEXT) return false
        return when (inputType and MASK_VARIATION) {
            VARIATION_PASSWORD, VARIATION_VISIBLE_PASSWORD, VARIATION_WEB_PASSWORD -> true
            else -> false
        }
    }
}

/**
 * Flattened description of an accessibility node (spec §5.1 / §10.1).
 * Snapshots store a flat list of these; hierarchy is encoded in [nodeId]
 * (path from the root, e.g. `r/3/1`) and [parentId].
 */
data class NodeSummary(
    val nodeId: String,
    val parentId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val viewIdResourceName: String? = null,
    val bounds: RectF = RectF.EMPTY,
    val isClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisibleToUser: Boolean = true,
    val isScrollable: Boolean = false,
    val isPassword: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isEditable: Boolean = false,
    val inputType: Int = 0,
    val depth: Int = 0,
    val windowId: Int = -1,
) {
    val isSensitive: Boolean
        get() = isPassword || InputTypes.isPasswordInputType(inputType)
}
