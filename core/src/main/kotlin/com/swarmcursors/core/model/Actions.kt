package com.swarmcursors.core.model

import com.swarmcursors.core.geometry.PointF
import java.util.UUID

/** Types of operations an agent can ask the library to perform. */
enum class ActionType {
    CLICK,
    LONG_CLICK,
    TYPE,
    SCROLL,
    SELECT,
    CLEAR_SELECTION,
    BACK,
    HOME,
}

/** Scroll directions for [ActionType.SCROLL]. */
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Where an [AgentAction] should be performed. A node id takes precedence over
 * coordinates when both are supplied; coordinates are the fallback when the
 * node cannot be resolved.
 */
data class ActionTarget(
    val nodeId: String? = null,
    val x: Float? = null,
    val y: Float? = null,
) {
    val isNode: Boolean get() = nodeId != null
    val hasCoordinates: Boolean get() = x != null && y != null

    fun coordinatePoint(): PointF? =
        if (hasCoordinates) PointF(x!!, y!!) else null

    companion object {
        fun node(nodeId: String): ActionTarget = ActionTarget(nodeId = nodeId)

        fun coordinate(x: Float, y: Float): ActionTarget = ActionTarget(x = x, y = y)
    }
}

/**
 * A single unit of work submitted by an agent. Actions are grouped into
 * tickets by [ActionTicket].
 *
 * Note: [text] is intentionally NOT validated in `init` so that sanitized
 * copies (audit log strips input text) can be produced via `copy()`.
 */
data class AgentAction(
    val id: String = UUID.randomUUID().toString(),
    val type: ActionType,
    val target: ActionTarget = ActionTarget(),
    /** Text to type when [type] is [ActionType.TYPE]. */
    val text: String? = null,
    /** Scroll direction when [type] is [ActionType.SCROLL]. */
    val direction: ScrollDirection = ScrollDirection.DOWN,
    /** Scroll amount in pages (1.0 = one page). */
    val amount: Float = 1f,
    /** Optional duration hint for the motion phase, overrides Fitts' law. */
    val durationHintMs: Long? = null,
    /** Free-form metadata attached by the agent. Never interpreted by the engine. */
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * A group of actions submitted atomically through
 * `SwarmCursors.injectActions`. Each ticket owns an independent event flow.
 */
data class ActionTicket(
    val id: String = UUID.randomUUID().toString(),
    val actions: List<AgentAction>,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    init {
        require(actions.isNotEmpty()) { "A ticket requires at least one action" }
    }
}
