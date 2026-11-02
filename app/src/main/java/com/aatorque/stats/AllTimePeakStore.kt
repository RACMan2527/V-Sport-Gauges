package com.aatorque.stats

import android.content.Context
import android.util.Base64
import com.aatorque.datastore.Display

/**
 * Stores all-time peaks against Torque's complete PID identifier.
 * Keeping the full identifier (including Torque's "torque_" prefix) supports
 * standard and Torque virtual PIDs without relying on dashboard positions.
 */
object AllTimePeakStore {
    private const val PREFERENCES_NAME = "all_time_peaks"

    fun key(pid: String): String {
        val encoded = Base64.encodeToString(
            pid.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "peak_$encoded"
    }

    fun get(context: Context, pid: String): Double? =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(key(pid), null)
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }

    /**
     * Reads the PID-only value, migrating a value saved by the previous
     * PID + unit + script key when that old display configuration is available.
     */
    fun get(context: Context, display: Display): Double? {
        get(context, display.pid)?.let { return it }
        val legacyIdentity = listOf(
            display.pid,
            display.unit,
            display.enableScript.toString(),
            if (display.enableScript) display.customScript else ""
        ).joinToString("|")
        val legacyEncoded = Base64.encodeToString(
            legacyIdentity.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val legacyValue = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString("peak_$legacyEncoded", null)
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }
        if (legacyValue != null && display.pid.isNotBlank()) {
            set(context, display.pid, legacyValue)
        }
        return legacyValue
    }

    fun set(context: Context, pid: String, value: Double) {
        if (!value.isFinite()) return
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key(pid), value.toString())
            .apply()
    }

    fun reset(context: Context, pid: String) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(pid))
            .apply()
    }
}
