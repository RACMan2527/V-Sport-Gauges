package com.aatorque.stats

import android.content.Context
import android.util.Base64

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
