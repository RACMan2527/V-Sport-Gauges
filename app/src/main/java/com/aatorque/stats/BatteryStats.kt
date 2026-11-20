package com.aatorque.stats

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import org.json.JSONObject
import java.util.Locale

/** Off-time battery sampling; no timers, alarms or additional wake locks. */
object BatteryStats {
    private const val PREF = "battery_stats"
    private const val HOUR_MS = 3_600_000L
    data class Display(val level: Int?, val charging: Boolean, val minutes: Long?,
        val start: Int?, val consumed: Int?, val rate: Double?, val remainingHours: Double?)

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun battery(context: Context): Pair<Int?, Boolean> {
        val data = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = data?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = data?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = data?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = data?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return (if (level >= 0 && scale > 0) (100.0 * level / scale).toInt() else null) to
            (plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL)
    }

    @Synchronized fun sample(context: Context, monitoring: Boolean, ecuDisconnected: Boolean) {
        val (level, charging) = battery(context)
        val p = prefs(context)
        val now = SystemClock.elapsedRealtime()
        val warning = p.getBoolean("warning", false)
        if (level != null) {
            val next = if (level <= 20) true else if (level >= 25) false else warning
            if (next != warning) p.edit().putBoolean("warning", next).apply()
        }
        val previousLevel = p.getInt("previousLevel", -1)
        val increased = level != null && previousLevel >= 0 && level > previousLevel
        if (level != null) p.edit().putInt("previousLevel", level).apply()
        val eligible = !charging && !increased && level != null
        val startAt = p.getLong("startElapsed", 0L)
        val bootAt = p.getLong("bootAt", 0L)
        val boot = System.currentTimeMillis() - now
        if (!eligible || (startAt != 0L && (now < startAt || kotlin.math.abs(boot - bootAt) > 60_000L))) {
            if (startAt != 0L) DiagnosticLog.event("BATTERY_CYCLE_INTERRUPTED", "charging=$charging increased=$increased valid=${level != null} reboot=${now < startAt || kotlin.math.abs(boot - bootAt) > 60_000L}")
            if (startAt != 0L) {
                p.edit().remove("startElapsed").remove("startLevel").remove("sampleCount")
                    .remove("bootAt").apply()
                AppStateStore.changed(context)
            }
            return
        }
        if (startAt == 0L) {
            p.edit().putLong("startElapsed", now).putLong("bootAt", boot)
                .putInt("startLevel", level!!).putInt("sampleCount", 0).apply()
            DiagnosticLog.event("BATTERY_CYCLE_START", "level=$level")
            AppStateStore.changed(context)
            return
        }
        if (level!! > p.getInt("startLevel", level)) {
            p.edit().remove("startElapsed").remove("startLevel").remove("sampleCount")
                .remove("bootAt").apply()
            AppStateStore.changed(context)
            return
        }
        // Use real elapsed time; a delayed Android wakeup is not an invalid sample.
        val elapsed = now - startAt
        if (elapsed >= 2L * HOUR_MS) {
            val startLevel = p.getInt("startLevel", level)
            p.edit().putLong("lastMinutes", elapsed / 60_000L)
                .putInt("lastStart", startLevel).putInt("lastEnd", level)
                .putLong("lastElapsed", elapsed)
                .remove("startElapsed").remove("startLevel").remove("sampleCount")
                .remove("bootAt").apply()
            DiagnosticLog.event("BATTERY_CYCLE_COMPLETE",
                "start=$startLevel end=$level elapsedMinutes=${elapsed / 60_000L}")
            AppStateStore.changed(context)
        }
    }

    @Synchronized fun interrupted(context: Context) {
        // Service shutdown does not terminate an otherwise valid battery cycle.
        // Periodic work will resume sampling when Android next permits it.
    }

    @Synchronized fun display(context: Context): Display {
        val (level, charging) = battery(context)
        val p = prefs(context)
        val activeAt = p.getLong("startElapsed", 0L)
        val active = !charging && activeAt > 0L && SystemClock.elapsedRealtime() >= activeAt &&
            (level == null || level <= p.getInt("startLevel", level))
        val start = if (active) p.getInt("startLevel", -1) else p.getInt("lastStart", -1)
        val end = if (active) level else p.getInt("lastEnd", -1).takeIf { it >= 0 }
        val minutes = if (active) (SystemClock.elapsedRealtime() - activeAt) / 60_000L
            else p.getLong("lastMinutes", -1L).takeIf { it >= 0L }
        val consumed = if (start >= 0 && end != null) (start - end).coerceAtLeast(0) else null
        val rate = if (minutes != null && minutes > 0 && consumed != null)
            consumed * 60.0 / minutes else null
        val remaining = if (rate != null && rate > 0.0 && level != null && level > 20)
            (level - 20) / rate else null
        return Display(level, charging, minutes, start.takeIf { it >= 0 }, consumed, rate, remaining)
    }

    fun warning(context: Context): Boolean = prefs(context).getBoolean("warning", false)

    @Synchronized fun snapshot(context: Context): JSONObject {
        val p = prefs(context)
        return JSONObject().put("lastMinutes", p.getLong("lastMinutes", -1L))
            .put("lastStart", p.getInt("lastStart", -1))
            .put("lastEnd", p.getInt("lastEnd", -1))
            .put("lastElapsed", p.getLong("lastElapsed", -1L))
            .put("warning", p.getBoolean("warning", false))
    }

    @Synchronized fun restore(context: Context, json: JSONObject?) {
        if (json == null) return
        val p = prefs(context)
        // Never overwrite a more recent completed cycle with an older AppState snapshot.
        if (p.getLong("lastElapsed", -1L) < 0L) {
            p.edit().putLong("lastMinutes", json.optLong("lastMinutes", -1L))
                .putInt("lastStart", json.optInt("lastStart", -1))
                .putInt("lastEnd", json.optInt("lastEnd", -1))
                .putLong("lastElapsed", json.optLong("lastElapsed", -1L))
                .putBoolean("warning", json.optBoolean("warning", false)).apply()
        }
    }
}
