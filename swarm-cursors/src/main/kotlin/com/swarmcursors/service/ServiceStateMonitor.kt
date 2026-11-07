package com.swarmcursors.service

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.swarmcursors.core.log.SwarmLog
import com.swarmcursors.core.model.ServiceState
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Publishes the [ServiceState] (spec §1.3).
 *
 * State is recomputed whenever:
 *  * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` / `ACCESSIBILITY_ENABLED`
 *    change (observed via [ContentObserver]), or
 *  * the service reports connect/disconnect (spec §2.1).
 *
 * A test override (installed by SwarmTestSupport) always wins.
 */
internal object ServiceStateMonitor {

    private val state = MutableStateFlow<ServiceState>(ServiceState.NotEnabled)
    val stateFlow: StateFlow<ServiceState> get() = state

    private val lock = Any()
    private var started = false
    private var appContext: Context? = null
    private val testOverride = AtomicReference<ServiceState?>(null)

    /** Idempotent; registers the settings observers once per process. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (started) return
            started = true
            this.appContext = appContext
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    recompute()
                }
            }
            val resolver = appContext.contentResolver
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer,
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                observer,
            )
            SwarmLog.d("ServiceStateMonitor started")
        }
        recompute()
    }

    fun current(): ServiceState = state.value

    /** Recomputes the state from settings + live service instance. */
    fun recompute() {
        val override = testOverride.get()
        if (override != null) {
            state.value = override
            return
        }
        val context = synchronized(lock) { appContext } ?: return
        val enabled = AccessibilitySettingsHelper.isServiceEnabled(context)
        val newState = when {
            !enabled -> ServiceState.NotEnabled
            SwarmAccessibilityService.getInstance() != null -> ServiceState.Ready
            else -> ServiceState.Connecting
        }
        if (newState != state.value) {
            SwarmLog.i("Service state: ${state.value} -> $newState")
        }
        state.value = newState
    }

    fun onServiceConnected() {
        val override = testOverride.get()
        if (override != null) return
        if (state.value != ServiceState.Ready) {
            SwarmLog.i("Service state: ${state.value} -> Ready")
        }
        state.value = ServiceState.Ready
    }

    fun onServiceDisconnected() {
        val override = testOverride.get()
        if (override != null) return
        recompute()
    }

    /** Test-only override installed by SwarmTestSupport. */
    fun setTestOverride(override: ServiceState?) {
        testOverride.set(override)
        if (override != null) {
            state.value = override
        } else {
            recompute()
        }
    }
}
