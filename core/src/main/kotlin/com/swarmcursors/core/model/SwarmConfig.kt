package com.swarmcursors.core.model

/**
 * Visual style of the cursor motion phase.
 */
enum class MotionStyle {
    LINEAR,
    EASE,
    EASE_IN_OUT,
    SPRING,
}

/**
 * Colors and sizes of the cursor visuals (spec §7.3). Colors are packed ARGB
 * ints; use [argb] to build them.
 */
data class CursorAppearance(
    val arrowColor: Int = 0xFFFFFFFF.toInt(),
    val arrowStrokeColor: Int = 0xFF000000.toInt(),
    val dotRadius: Float = 6f,
    val rippleColor: Int = argb(120, 66, 133, 244),
    val labelBackgroundColor: Int = argb(200, 0, 0, 0),
    val labelTextColor: Int = 0xFFFFFFFF.toInt(),
) {
    companion object {
        /** Packs alpha/red/green/blue into an ARGB int (no Android dependency). */
        fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
            (alpha.coerceIn(0, 255) shl 24) or
                (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or
                blue.coerceIn(0, 255)
    }
}

/**
 * Retry behaviour for failed actions (spec §4.2). By default only
 * [FailureReason.NODE_STALE] and [FailureReason.EXECUTION_REJECTED] are
 * retried once.
 */
data class RetryPolicy(
    val maxRetries: Int = 1,
    val retryOn: Set<FailureReason> = setOf(FailureReason.NODE_STALE, FailureReason.EXECUTION_REJECTED),
    val backoffMs: Long = 200,
) {
    fun shouldRetry(reason: FailureReason, attemptsMade: Int): Boolean =
        reason in retryOn && attemptsMade <= maxRetries
}

/**
 * Global library configuration (spec §7.1).
 *
 * Hot-updatable fields (applied from the next frame): [maxCursors],
 * [motionStyle], [cursorScale], [enableTrail], [enableActionLabel], [theme],
 * [appearance], [retryPolicy], [actionTimeoutMs], snapshot timings.
 *
 * Fields that force an overlay rebuild: [parallelMode], [maxParallel].
 */
data class SwarmConfig(
    val maxCursors: Int = 8,
    val motionStyle: MotionStyle = MotionStyle.EASE_IN_OUT,
    val cursorScale: Float = 1f,
    val enableTrail: Boolean = true,
    val enableActionLabel: Boolean = true,
    val theme: String = "default",
    val parallelMode: Boolean = false,
    val maxParallel: Int = 2,
    /** Default per-action timeout for both motion and execution (spec §3.3). */
    val actionTimeoutMs: Long = 5_000,
    /** Delay applied by `captureSnapshot` before the first probe (spec §5.2). */
    val snapshotDelayMs: Long = 200,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val appearance: CursorAppearance = CursorAppearance(),
) {
    init {
        require(maxCursors >= 1) { "maxCursors must be >= 1" }
        require(maxParallel >= 1) { "maxParallel must be >= 1" }
        require(actionTimeoutMs > 0) { "actionTimeoutMs must be > 0" }
        require(cursorScale > 0f) { "cursorScale must be > 0" }
    }

    /** Effective number of concurrently running tickets (spec §3.1). */
    val effectiveParallelism: Int
        get() = (if (parallelMode) maxParallel else 1).coerceIn(1, maxCursors)
}
