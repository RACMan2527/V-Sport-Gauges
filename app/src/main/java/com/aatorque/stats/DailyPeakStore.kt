package com.aatorque.stats

import android.content.Context
import android.util.Base64
import com.aatorque.datastore.Display
import java.time.LocalDate

/**
 * Persisted local-calendar-day extrema, shared by dashboards using the same
 * Torque PID and conversion. A new date starts with no daily peak.
 */
object DailyPeakStore {
    private const val PREFS = "daily_pid_peaks"

    private fun key(display: Display): String {
        val identity = listOf(display.pid, display.enableScript.toString(),
            if (display.enableScript) display.customScript else "").joinToString("\u0000")
        return Base64.encodeToString(identity.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    @Synchronized
    fun record(context: Context, display: Display, reading: Double) {
        if (!reading.isFinite() || display.pid.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val id = key(display)
        if (prefs.getString("date", null) != today) {
            prefs.edit().clear().putString("date", today).commit()
        }
        val previousMax = prefs.getString("max_$id", null)?.toDoubleOrNull()
        val previousMin = prefs.getString("min_$id", null)?.toDoubleOrNull()
        val edit = prefs.edit()
        if (previousMax == null || reading > previousMax) edit.putString("max_$id", reading.toString())
        if (previousMin == null || reading < previousMin) edit.putString("min_$id", reading.toString())
        edit.apply()
    }

    @Synchronized
    fun get(context: Context, display: Display, minimum: Boolean = false): Double? {
        if (display.pid.isBlank()) return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("date", null) != LocalDate.now().toString()) return null
        return prefs.getString((if (minimum) "min_" else "max_") + key(display), null)
            ?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
