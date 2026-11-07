package com.swarmcursors.security

import com.swarmcursors.core.log.SwarmLog
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.AuditEntry

/**
 * Action audit trail (spec §10.2). Records only action type, target and
 * result — input text is ALWAYS stripped, even when the target is not a
 * password field.
 */
internal class AuditLogger {

    @Volatile
    private var listener: ((AuditEntry) -> Unit)? = null

    fun setListener(newListener: ((AuditEntry) -> Unit)?) {
        listener = newListener
    }

    fun record(
        action: AgentAction,
        result: Boolean,
        durationMs: Long,
        timestampMs: Long,
    ) {
        val sink = listener ?: return
        val sanitized = if (action.text == null) action else action.copy(text = null)
        val entry = AuditEntry(
            timestampMs = timestampMs,
            action = sanitized,
            result = result,
            durationMs = durationMs,
        )
        try {
            sink(entry)
        } catch (t: Throwable) {
            SwarmLog.e("Audit listener threw", t)
        }
    }
}
