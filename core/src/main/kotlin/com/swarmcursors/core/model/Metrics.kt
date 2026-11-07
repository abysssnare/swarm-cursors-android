package com.swarmcursors.core.model

/**
 * Aggregated library metrics (spec §8.2).
 */
data class SwarmMetrics(
    val totalActions: Long,
    val succeeded: Long,
    val failed: Long,
    val avgMotionDurationMs: Long,
    val avgExecutionDurationMs: Long,
    val avgSnapshotDurationMs: Long,
    val currentFps: Int,
    val avgFrameTimeMs: Float,
)

/**
 * Audit entry for a performed action (spec §10.2). [action] never contains
 * typed text — it is stripped before recording.
 */
data class AuditEntry(
    val timestampMs: Long,
    val action: AgentAction,
    val result: Boolean,
    val durationMs: Long,
)
