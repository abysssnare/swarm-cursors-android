package com.swarmcursors.core.model

/**
 * Result of comparing two snapshots by node id (spec §5.1).
 */
data class SnapshotDiff(
    val added: List<NodeSummary>,
    val removed: List<String>,
    val changed: List<ChangedNode>,
)

/** A node that exists in both snapshots but whose properties changed. */
data class ChangedNode(
    val nodeId: String,
    val previous: NodeSummary,
    val current: NodeSummary,
)

/**
 * Capture-time knobs for `captureSnapshot` (spec §5.3 / §10.1).
 */
data class SnapshotConfig(
    /** Hard cap on the number of nodes. Excess nodes are dropped by
     * visibility-then-area priority. */
    val maxNodes: Int = 500,
    /** Include nodes not visible to the user (default false). */
    val includeInvisible: Boolean = false,
    /** Compute a diff against the previous snapshot (first snapshot has
     * `diff = null`). */
    val computeDiff: Boolean = true,
    /** When false (default) text of password-like nodes is redacted. */
    val includeSensitiveText: Boolean = false,
    /** Leading settle delay applied before the first capture (spec §5.2). */
    val settleDelayMs: Long = 200,
    /** Delay between the two stability probes (spec §5.2). */
    val stabilityIntervalMs: Long = 80,
    /** Bounds delta below which the UI is considered stable (spec §5.2). */
    val stabilityThresholdPx: Float = 5f,
    /** Maximum number of stability re-checks before returning unstable. */
    val maxStabilityRetries: Int = 3,
)

/**
 * A (possibly incremental) snapshot of the interface (spec §5.1).
 */
data class InterfaceSnapshot(
    val nodes: List<NodeSummary>,
    val timestampMs: Long,
    val windowTitle: String? = null,
    val windowId: Int = -1,
    val diff: SnapshotDiff? = null,
    /** False when stability detection gave up after [SnapshotConfig.maxStabilityRetries]. */
    val isStable: Boolean = true,
)
