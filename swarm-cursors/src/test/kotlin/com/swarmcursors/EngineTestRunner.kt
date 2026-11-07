package com.swarmcursors

import com.swarmcursors.core.geometry.RectF
import com.swarmcursors.core.model.NodeSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.swarmcursors.test.SwarmTestSupport

/**
 * Shared runner for engine tests: installs a virtual-time main dispatcher,
 * drains the engine at the end and always resets global state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun runEngineTest(block: suspend TestScope.() -> Unit) = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
        block()
        advanceUntilIdle()
    } finally {
        SwarmTestSupport.reset()
        SwarmCursors.shutdown()
        Dispatchers.resetMain()
    }
}

/** Convenience node factory for tests. */
internal fun testNode(
    id: String,
    text: String? = null,
    left: Float = 100f,
    top: Float = 800f,
    right: Float = 500f,
    bottom: Float = 880f,
    enabled: Boolean = true,
    visible: Boolean = true,
    password: Boolean = false,
) = NodeSummary(
    nodeId = id,
    text = text,
    bounds = RectF(left, top, right, bottom),
    isClickable = true,
    isEnabled = enabled,
    isVisibleToUser = visible,
    isPassword = password,
)
