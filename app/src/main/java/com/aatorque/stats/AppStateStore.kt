package com.aatorque.stats

import android.content.Context
import android.content.SharedPreferences
import com.aatorque.prefs.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Perpetual state journal. All document I/O and protobuf restoration run on one
 * background executor, never on the gauge drawing or Torque polling threads.
 */
object AppStateStore {
    private const val NAME = "AppState.json"
    private val io = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "V-Sport-AppState").apply { isDaemon = true }
    }
    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var initialized = false
    @Volatile private var restoring = false
    private var app: Context? = null
    private var firstDirtyAtMs = 0L

    fun initialize(context: Context) {
        synchronized(lock) {
            if (app != null) return
            app = context.applicationContext
            io.execute { recoverOrSeed(context.applicationContext) }
        }
    }

    /** Called after SAF authorization, before any public state is written. */
    fun onFolderAuthorized(context: Context, finished: (Boolean, String?) -> Unit) {
        initialize(context)
        io.execute {
            val result = runCatching { recoverOrSeed(context.applicationContext) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                finished(result.isSuccess, result.exceptionOrNull()?.message)
            }
        }
    }

    private fun recoverOrSeed(context: Context) {
        if (!PublicDocuments.hasFolderAccess(context)) {
            initialized = false
            return
        }
        synchronized(lock) { pending?.cancel(false); pending = null; firstDirtyAtMs = 0L }
        initialized = false
        val existing = PublicDocuments.readExisting(context, PublicDocuments.ROOT, NAME)
        if (existing != null) {
            val json = JSONObject(String(existing, Charsets.UTF_8))
            require(json.optInt("schema") == 1 &&
                json.has("peaks") && json.has("daily") && json.has("gaugeSizes")) {
                "Unsupported or incomplete AppState.json; original file preserved"
            }
            restoring = true
            try { restore(context, json) } finally { restoring = false }
            writeSnapshot(context)
            DiagnosticLog.event("APP_STATE_RECOVERED", "bytes=${existing.size}")
        } else {
            writeSnapshot(context)
            DiagnosticLog.event("APP_STATE_INITIALIZED")
        }
        initialized = true
    }

    /** Only changed records schedule a write; successive updates are coalesced. */
    fun changed(context: Context) {
        initialize(context)
        if (restoring) return
        synchronized(lock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (firstDirtyAtMs == 0L) firstDirtyAtMs = now
            val delay = minOf(1500L, (10000L - (now - firstDirtyAtMs)).coerceAtLeast(0L))
            pending?.cancel(false)
            pending = io.schedule({
                if (initialized && PublicDocuments.hasFolderAccess(context)) {
                    runCatching { writeSnapshot(context.applicationContext) }
                        .onFailure { DiagnosticLog.event("APP_STATE_SAVE_ERROR",
                            it.javaClass.simpleName + " " + it.message) }
                }
                synchronized(lock) { firstDirtyAtMs = 0L; pending = null }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun prefs(context: Context, name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun snapshotPrefs(source: SharedPreferences, prefix: String): JSONObject =
        JSONObject().also { out ->
            source.all.forEach { (key, value) ->
                if (key.startsWith(prefix) && value is String) out.put(key, value)
            }
        }

    private fun snapshot(context: Context): JSONObject {
        val daily = prefs(context, "daily_pid_peaks")
        val date = daily.getString("date", null)
        val center = runBlocking { context.dataStore.data.first().centerGaugeLarge }
        return JSONObject()
            .put("schema", 1)
            .put("peaks", snapshotPrefs(prefs(context, "all_time_peaks"), "peak_"))
            .put("daily", JSONObject()
                .put("date", date ?: "")
                .put("values", if (date == LocalDate.now().toString()) {
                    JSONObject().also { out ->
                        daily.all.forEach { (key, value) ->
                            if ((key.startsWith("min_") || key.startsWith("max_")) &&
                                value is String) out.put(key, value)
                        }
                    }
                } else JSONObject()))
            .put("gaugeSizes", JSONObject()
                .put("all", prefs(context, "round_gauge_display")
                    .getBoolean("all_round_gauges_large", false))
                .put("center", center))
            .put("batteryStats", BatteryStats.snapshot(context))
            .put("aaContentWidthPx", prefs(context, "aa_content_area").getInt("width_px", 0))
            .put("aaContentHeightPx", prefs(context, "aa_content_area").getInt("height_px", 0))
            .put("gaugeTypography", JSONObject().also { out ->
                val settings = prefs(context, "gauge_typography")
                out.put("roundFont", settings.getInt("round_font", 1))
                out.put("roundTickOffset", settings.getInt("round_tick_offset", 0))
                out.put("horizontalFont", settings.getInt("horizontal_font", 1))
                out.put("viewFont", settings.getInt("view_font", 1))
            })
    }

    private fun writeSnapshot(context: Context) {
        val bytes = snapshot(context).toString(2).toByteArray(Charsets.UTF_8)
        PublicDocuments.write(context, PublicDocuments.ROOT, NAME, "application/json", bytes)
    }

    private fun restore(context: Context, json: JSONObject) {
        val peakJson = json.getJSONObject("peaks")
        val peaks = prefs(context, "all_time_peaks")
        val peakEditor = peaks.edit()
        val peakKeys = peakJson.keys()
        while (peakKeys.hasNext()) {
            val key = peakKeys.next()
            val value = peakJson.optString(key).toDoubleOrNull()
            if (key.startsWith("peak_") && value != null && value.isFinite()) {
                val old = peaks.getString(key, null)?.toDoubleOrNull()
                // Do not lose a newer peak recorded in the current installation.
                if (old == null || value > old) peakEditor.putString(key, value.toString())
            }
        }
        check(peakEditor.commit()) { "Cannot restore peak preferences" }

        val dailyJson = json.getJSONObject("daily")
        val today = LocalDate.now().toString()
        val daily = prefs(context, "daily_pid_peaks")
        val dailyEditor = daily.edit()
        if (dailyJson.optString("date") == today) {
            if (daily.getString("date", null) != today) dailyEditor.clear()
            val values = dailyJson.getJSONObject("values")
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = values.optString(key).toDoubleOrNull()
                if (value == null || !value.isFinite()) continue
                if (!key.startsWith("min_") && !key.startsWith("max_")) continue
                val local = if (daily.getString("date", null) == today)
                    daily.getString(key, null)?.toDoubleOrNull() else null
                val use = when {
                    local == null -> value
                    key.startsWith("min_") -> minOf(local, value)
                    else -> maxOf(local, value)
                }
                dailyEditor.putString(key, use.toString())
            }
            dailyEditor.putString("date", today)
        } else if (daily.getString("date", null) != today) {
            dailyEditor.clear().putString("date", today)
        }
        check(dailyEditor.commit()) { "Cannot restore daily preferences" }

        json.optJSONObject("gaugeTypography")?.let { typography ->
            val editor = prefs(context, "gauge_typography").edit()
            if (typography.has("roundFont")) editor.putInt("round_font", typography.optInt("roundFont", 1).coerceIn(0, 2))
            if (typography.has("roundTickOffset")) editor.putInt("round_tick_offset", typography.optInt("roundTickOffset", 0).coerceIn(-25, 25))
            if (typography.has("horizontalFont")) editor.putInt("horizontal_font", typography.optInt("horizontalFont", 1).coerceIn(0, 2))
            if (typography.has("viewFont")) editor.putInt("view_font", typography.optInt("viewFont", 1).coerceIn(0, 2))
            check(editor.commit()) { "Cannot restore gauge typography" }
        }
        // Optional, additive fields: older AppState files remain compatible.
        val aaWidth = json.optInt("aaContentWidthPx", 0)
        val aaHeight = json.optInt("aaContentHeightPx", 0)
        if (aaWidth > 0 && aaHeight > 0) {
            val aa = prefs(context, "aa_content_area")
            if (aa.getInt("width_px", 0) <= 0 || aa.getInt("height_px", 0) <= 0) {
                check(aa.edit().putInt("width_px", aaWidth)
                    .putInt("height_px", aaHeight).commit())
            }
        }
        BatteryStats.restore(context, json.optJSONObject("batteryStats"))
        val sizes = json.getJSONObject("gaugeSizes")
        val all = sizes.getBoolean("all")
        val center = sizes.getBoolean("center")
        check(prefs(context, "round_gauge_display").edit()
            .putBoolean("all_round_gauges_large", all).commit())
        runBlocking {
            context.dataStore.updateData { current ->
                current.toBuilder().setCenterGaugeLarge(center).build()
            }
        }
    }
}
