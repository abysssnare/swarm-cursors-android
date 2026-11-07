package com.swarmcursors.core.model

/** Stage an action was in when it got cancelled. */
enum class CancelStage {
    /** Cancelled while still waiting in the queue. */
    QUEUED,

    /** Cancelled while the cursor was still travelling to the target. */
    MOVING,

    /** Cancelled right before the real accessibility action was dispatched. */
    EXECUTING,
}

/**
 * Events emitted by `SwarmCursors.injectActions(...)` for a single ticket.
 * The flow completes after [TicketCompleted] (or after a terminal failure
 * such as `SERVICE_NOT_READY`).
 */
sealed class ActionEvent {

    /** The ticket was accepted into the queue. [position] is 0-based. */
    data class TicketQueued(val ticketId: String, val position: Int) : ActionEvent()

    /** A single action within the ticket started (resolution + motion). */
    data class ActionStarted(val action: AgentAction) : ActionEvent()

    /** Cursor motion progress. [progress] in `[0f, 1f]`. */
    data class Moving(
        val action: AgentAction,
        val progress: Float,
        val x: Float,
        val y: Float,
    ) : ActionEvent()

    /** The real accessibility action is being dispatched now. */
    data class Executing(val action: AgentAction) : ActionEvent()

    /** The action finished successfully (or the system reported success). */
    data class Executed(
        val action: AgentAction,
        val result: Boolean,
        val durationMs: Long,
        val attempts: Int = 1,
    ) : ActionEvent()

    /** The action failed. [action] is null for ticket-level failures. */
    data class Failed(
        val action: AgentAction?,
        val reason: FailureReason,
        val message: String? = null,
        val attempts: Int = 1,
    ) : ActionEvent()

    /** The action (or the whole ticket when [action] is null) was cancelled. */
    data class Cancelled(
        val action: AgentAction?,
        val stage: CancelStage,
    ) : ActionEvent()

    /** Terminal event for a ticket. */
    data class TicketCompleted(
        val ticketId: String,
        val succeeded: Int,
        val failed: Int,
        val cancelled: Int,
    ) : ActionEvent()
}
