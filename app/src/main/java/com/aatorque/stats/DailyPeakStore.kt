package com.aatorque.stats

import android.content.Context
import android.util.Base64
import com.aatorque.datastore.Display
import com.aatorque.datastore.MaxControl
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

    private fun preferencesForToday(context: Context): android.content.SharedPreferences {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString("date", null) != today) {
            prefs.edit().clear().putString("date", today).commit()
        }
        return prefs
    }


    // Notification gating is session-local; persisted daily extrema remain authoritative.
    // Shared PID/conversion identity prevents dashboard switches from rearming notifications.
    private data class BlinkState(var baseline: Double, var lastBlinkAt: Long = -1L)
    private val blinkStates = mutableMapOf<String, BlinkState>()
    private var blinkDate = LocalDate.now().toString()
    private const val BLINK_COOLDOWN_MS = 60_000L
    private const val BLINK_RANGE_FRACTION = 0.05

    @Synchronized
    fun shouldBlink(
        display: Display, minimum: Boolean, currentRecord: Double?,
        recordImproved: Boolean, nowMs: Long = android.os.SystemClock.elapsedRealtime()
    ): Boolean {
        val today = LocalDate.now().toString()
        if (blinkDate != today) {
            blinkStates.clear()
            blinkDate = today
        }
        if (currentRecord == null || !currentRecord.isFinite() || display.pid.isBlank()) return false
        val direction = if (minimum) "min" else "max"
        val identity = direction + "_" + key(display)
        val state = blinkStates[identity]
        if (state == null) {
            blinkStates[identity] = BlinkState(currentRecord)
            return false
        }
        if (!recordImproved) return false
        val range = display.maxValue.toDouble() - display.minValue.toDouble()
        if (!range.isFinite() || range <= 0.0) return false
        val change = if (minimum) state.baseline - currentRecord else currentRecord - state.baseline
        if (change < range * BLINK_RANGE_FRACTION) return false
        if (state.lastBlinkAt >= 0L && nowMs - state.lastBlinkAt < BLINK_COOLDOWN_MS) return false
        state.baseline = currentRecord
        state.lastBlinkAt = nowMs
        return true
    }

    @Synchronized
    fun isNewMinimum(context: Context, display: Display, reading: Double): Boolean {
        if (!reading.isFinite() || display.pid.isBlank() ||
            display.maxMarksActive != MaxControl.MIN && display.maxMarksActive != MaxControl.BOTH) return false
        val previous = get(context, display, true)
        return previous == null || reading < previous
    }

    @Synchronized
    fun recordMaximum(context: Context, display: Display, reading: Double, horizontalBar: Boolean = false) {
        if (!reading.isFinite() || display.pid.isBlank() ||
            (display.maxMarksActive != MaxControl.MAX && display.maxMarksActive != MaxControl.BOTH && !horizontalBar)) return
        val prefs = preferencesForToday(context)
        val name = "max_${key(display)}"
        val previous = prefs.getString(name, null)?.toDoubleOrNull()
        if (previous == null || reading > previous) {
            prefs.edit().putString(name, reading.toString()).apply()
        }
    }

    @Synchronized
    fun recordMinimum(context: Context, display: Display, reading: Double, candidateDate: String) {
        if (!reading.isFinite() || display.pid.isBlank() ||
            (display.maxMarksActive != MaxControl.MIN && display.maxMarksActive != MaxControl.BOTH) ||
            candidateDate != LocalDate.now().toString()) return
        val prefs = preferencesForToday(context)
        val name = "min_${key(display)}"
        val previous = prefs.getString(name, null)?.toDoubleOrNull()
        if (previous == null || reading < previous) {
            prefs.edit().putString(name, reading.toString()).apply()
        }
    }

    /** Clear today's extrema for all PID identities, preserving the other direction. */
    @Synchronized
    fun resetToday(context: Context, resetMin: Boolean, resetMax: Boolean) {
        val prefs = preferencesForToday(context)
        val editor = prefs.edit()
        prefs.all.keys.forEach { name ->
            if ((resetMin && name.startsWith("min_")) ||
                (resetMax && name.startsWith("max_"))) editor.remove(name)
        }
        editor.commit()
        val prefixes = listOfNotNull(if (resetMin) "min_" else null, if (resetMax) "max_" else null)
        blinkStates.keys.removeAll { identity -> prefixes.any { identity.startsWith(it) } }
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
