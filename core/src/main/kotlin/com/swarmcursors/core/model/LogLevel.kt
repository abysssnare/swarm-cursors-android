package com.swarmcursors.core.model

/** Log levels used by [com.swarmcursors.core.log.SwarmLog] (spec §8.1). */
enum class LogLevel {
    NONE,
    ERROR,
    WARN,
    INFO,
    DEBUG,
    VERBOSE;

    val passesError: Boolean get() = this >= ERROR
    val passesWarn: Boolean get() = this >= WARN
    val passesInfo: Boolean get() = this >= INFO
    val passesDebug: Boolean get() = this >= DEBUG
    val passesVerbose: Boolean get() = this >= VERBOSE
}
