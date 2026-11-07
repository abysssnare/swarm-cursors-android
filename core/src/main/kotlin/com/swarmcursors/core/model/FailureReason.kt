package com.swarmcursors.core.model

/**
 * Structured failure reasons used across the library (spec §4.1).
 */
enum class FailureReason {
    /** The accessibility service is disabled or not connected yet. */
    SERVICE_NOT_READY,

    /** Neither a node id nor usable coordinates were available. */
    TARGET_NOT_FOUND,

    /** The node existed when captured but the UI has changed since. */
    NODE_STALE,

    /** The cursor did not reach the target within the motion timeout. */
    MOTION_TIMEOUT,

    /** The real accessibility action did not complete within the timeout. */
    EXECUTION_TIMEOUT,

    /** `performAction` returned false. */
    EXECUTION_REJECTED,

    /** The system refused the operation (e.g. on the settings page). */
    PERMISSION_DENIED,

    /** Capturing an interface snapshot failed. */
    SNAPSHOT_FAILED,

    /** Reserved marker used when a failure is reported as a cancellation. */
    CANCELLED;

    val code: String get() = name
}
