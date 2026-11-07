package com.swarmcursors.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun `default policy retries stale nodes and rejections once`() {
        val policy = RetryPolicy()
        assertTrue(policy.shouldRetry(FailureReason.NODE_STALE, attemptsMade = 1))
        assertTrue(policy.shouldRetry(FailureReason.EXECUTION_REJECTED, attemptsMade = 1))
        assertFalse(policy.shouldRetry(FailureReason.NODE_STALE, attemptsMade = 2))
        assertFalse(policy.shouldRetry(FailureReason.MOTION_TIMEOUT, attemptsMade = 1))
        assertFalse(policy.shouldRetry(FailureReason.TARGET_NOT_FOUND, attemptsMade = 1))
    }

    @Test
    fun `custom policy is honored`() {
        val policy = RetryPolicy(
            maxRetries = 2,
            retryOn = setOf(FailureReason.MOTION_TIMEOUT),
            backoffMs = 50,
        )
        assertTrue(policy.shouldRetry(FailureReason.MOTION_TIMEOUT, attemptsMade = 2))
        assertFalse(policy.shouldRetry(FailureReason.MOTION_TIMEOUT, attemptsMade = 3))
        assertFalse(policy.shouldRetry(FailureReason.NODE_STALE, attemptsMade = 1))
    }

    @Test
    fun `effective parallelism respects sequential mode and cursor budget`() {
        val sequential = SwarmConfig(parallelMode = false, maxParallel = 4, maxCursors = 8)
        assertEquals(1, sequential.effectiveParallelism)

        val parallel = SwarmConfig(parallelMode = true, maxParallel = 4, maxCursors = 2)
        assertEquals(2, parallel.effectiveParallelism)

        val unlimited = SwarmConfig(parallelMode = true, maxParallel = 4, maxCursors = 8)
        assertEquals(4, unlimited.effectiveParallelism)
    }
}
