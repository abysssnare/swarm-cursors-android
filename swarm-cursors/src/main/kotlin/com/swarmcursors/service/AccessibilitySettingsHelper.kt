package com.swarmcursors.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.swarmcursors.core.log.SwarmLog

/**
 * Helpers around accessibility settings (spec §1.1/§1.2).
 */
internal object AccessibilitySettingsHelper {

    /** True when our service appears in the enabled services list. */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, SwarmAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        for (entry in enabled.split(':')) {
            if (matches(entry, expected)) return true
        }
        return false
    }

    /** Opens the system accessibility settings page (spec §1.2). */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            SwarmLog.i("Opened accessibility settings for ${context.packageName}")
        } catch (t: Throwable) {
            SwarmLog.e("Could not open accessibility settings", t)
        }
    }

    private fun matches(entry: String, expected: ComponentName): Boolean {
        if (entry.isBlank()) return false
        if (entry.equals(expected.flattenToString(), ignoreCase = true)) return true
        val parsed = ComponentName.unflattenFromString(entry) ?: return false
        if (parsed == expected) return true
        // Expand the short class form "com.pkg/.Svc".
        val cls = parsed.className ?: return false
        if (cls.startsWith(".") && parsed.packageName == expected.packageName) {
            return ComponentName(parsed.packageName, parsed.packageName + cls) == expected
        }
        return false
    }
}
