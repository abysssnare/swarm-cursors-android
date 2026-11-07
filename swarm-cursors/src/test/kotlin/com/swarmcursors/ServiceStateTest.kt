package com.swarmcursors

import android.content.ComponentName
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.swarmcursors.core.model.ServiceState
import com.swarmcursors.service.SwarmAccessibilityService
import com.swarmcursors.test.SwarmTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec §1.1/§1.2/§1.3: settings detection, state flow and jump to settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceStateTest {

    @Test
    fun `isAccessibilityServiceEnabled reflects secure settings`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertFalse(SwarmCursors.isAccessibilityServiceEnabled(context))

        val component = ComponentName(context, SwarmAccessibilityService::class.java)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.other.app/.TheirService",
        )
        assertFalse("Foreign services must not count", SwarmCursors.isAccessibilityServiceEnabled(context))

        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            component.flattenToString(),
        )
        assertTrue(SwarmCursors.isAccessibilityServiceEnabled(context))

        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "",
        )
        assertFalse(SwarmCursors.isAccessibilityServiceEnabled(context))
    }

    @Test
    fun `observeServiceState emits current state immediately`() = runEngineTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // No service enabled in a fresh Robolectric app.
        val state = SwarmCursors.observeServiceState(context).first()
        assertTrue(
            "Expected NotEnabled or Connecting, got $state",
            state is ServiceState.NotEnabled || state is ServiceState.Connecting,
        )
    }

    @Test
    fun `mock install reports ready`() = runEngineTest {
        assertEquals(ServiceState.NotEnabled, SwarmCursors.getServiceState())
        SwarmTestSupport.installMockService(listOf(testNode("x")))
        assertEquals(ServiceState.Ready, SwarmCursors.getServiceState())
        SwarmTestSupport.reset()
    }

    @Test
    fun `keep alive notification builds`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notification = SwarmCursors.createKeepAliveNotification(context)
        assertEquals("Swarm Cursors active", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
    }
}
