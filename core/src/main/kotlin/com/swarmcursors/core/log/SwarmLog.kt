package com.swarmcursors.core.log

import com.swarmcursors.core.model.LogLevel

/**
 * Central logger (spec §8.1). Defaults to [LogLevel.WARN] and the tag
 * `SwarmCursors`. Integrators can redirect output via [setLogger].
 */
object SwarmLog {

    const val TAG = "SwarmCursors"

    @Volatile
    private var level: LogLevel = LogLevel.WARN

    @Volatile
    private var sink: (LogLevel, String, String?, Throwable?) -> Unit =
        { lvl: LogLevel, tag: String, message: String, error: Throwable? ->
            when (lvl) {
                LogLevel.ERROR -> println("E/$tag: $message${error?.let { " — $it" } ?: ""}")
                LogLevel.WARN -> println("W/$tag: $message${error?.let { " — $it" } ?: ""}")
                LogLevel.INFO -> println("I/$tag: $message")
                LogLevel.DEBUG -> println("D/$tag: $message")
                LogLevel.VERBOSE -> println("V/$tag: $message")
                LogLevel.NONE -> Unit
            }
        }

    /** Sets the minimum level that is emitted. */
    fun setLevel(newLevel: LogLevel) {
        level = newLevel
    }

    /** Returns the current level. */
    fun getLevel(): LogLevel = level

    /**
     * Installs a custom output sink. It receives `(level, tag, message, throwable)`.
     * Pass a no-op lambda to silence the library completely.
     */
    fun setLogger(logger: (LogLevel, String, String?, Throwable?) -> Unit) {
        sink = logger
    }

    fun e(message: String, error: Throwable? = null) = dispatch(LogLevel.ERROR, message, error)
    fun w(message: String, error: Throwable? = null) = dispatch(LogLevel.WARN, message, error)
    fun i(message: String, error: Throwable? = null) = dispatch(LogLevel.INFO, message, error)
    fun d(message: String, error: Throwable? = null) = dispatch(LogLevel.DEBUG, message, error)
    fun v(message: String, error: Throwable? = null) = dispatch(LogLevel.VERBOSE, message, error)

    private fun dispatch(lvl: LogLevel, message: String, error: Throwable?) {
        val current = level
        if (current == LogLevel.NONE || lvl > current) return
        if (lvl == LogLevel.NONE) return
        try {
            sink(lvl, TAG, message, error)
        } catch (_: Throwable) {
            // Never let logging break the caller.
        }
    }
}
