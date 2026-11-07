package com.swarmcursors.core.model

/**
 * Lifecycle state of the accessibility service binding (spec §1.1).
 */
sealed class ServiceState {

    /** The accessibility service is not enabled in system settings. */
    object NotEnabled : ServiceState()

    /** The service is enabled; the system is binding / the library is connecting. */
    object Connecting : ServiceState()

    /** The service is connected and ready to execute actions. */
    object Ready : ServiceState()

    /** The service entered an error state; [reason] describes what happened. */
    data class Error(val reason: String) : ServiceState()
}
