package com.aatorque.stats

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.prowl.torque.remote.ITorqueService
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Independent tank accounting. Reads standard OBD PIDs using Torque's API; it never
 * modifies the gauge polling schedule. No simulated or invented tank statistics.
 *
 * 012F fuel level (%), 010D vehicle speed (km/h), 015E engine fuel rate (L/h).
 * If fuel rate is unavailable, distance/time remain valid but MPG and gallons
 * are deliberately unavailable. No assumption about the vehicle's tank capacity.
 */
class FuelTracker(context: Context, private val service: TorqueService) {
    data class Summary(val id: Long, val miles: Double, val gallons: Double?,
                       val seconds: Long, val mpg: Double?)
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, "Fuel-Tracker.json"))
    private val historyFile = AtomicFile(File(app.filesDir, "Fuel-Up-History.csv"))
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "V-Sport-Fuel-Tracker").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null
    private var state = load().also { writeHistory(it) }
    private var lastSampleMs = 0L
    private var candidate: Double? = null
    private var candidateCount = 0
    private var candidateBaseline: Double? = null
    private var warnedNoFuelTimestamp = false
    @Volatile private var latest: Summary? = null

    private fun emptyState(): JSONObject = JSONObject().put("schema", 2)
        .put("miles", 0.0).put("gallons", 0.0).put("seconds", 0L)
        .put("fuelRateValid", true).put("fuelRateSeen", false)
        .put("completed", JSONArray())

    private fun load(): JSONObject = try {
        val bytes = try {
            file.openRead().use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            PublicDocuments.readFuelDocument(app, "Fuel-Tracker.json")
                ?: throw IllegalStateException("Link existing Fuel-Tracker.json in phone Settings before starting")
        }
        JSONObject(String(bytes, Charsets.UTF_8)).also {
            require(it.has("miles") && it.has("seconds") && it.optJSONArray("completed") != null) {
                "Incomplete fuel tracker state"
            }
        }
    } catch (e: Exception) {
        // Never replace a damaged or unreadable tank with an empty one.
        DiagnosticLog.event("FUEL_TRACKER_LOAD_ERROR", e.javaClass.simpleName)
        throw IllegalStateException("Cannot recover Fuel-Tracker.json; existing data preserved", e)
    }


    /**
     * Completed tank JSON remains the atomic recovery journal. CSV is a separate,
     * rebuildable user-readable history. Rebuild at startup and after each refill:
     * an interrupted CSV write cannot lose or duplicate completed tank records.
     */
    private fun writeHistory(source: JSONObject) {
        val completed = source.getJSONArray("completed")
        val csv = StringBuilder("Fuel-Up ID,Date (UTC),Miles,Gallons,MPG,Driving Seconds,Fuel Before (%),Fuel After (%)\r\n")
        val seen = HashSet<Long>()
        for (i in 0 until completed.length()) {
            val tank = completed.getJSONObject(i)
            val id = tank.getLong("id")
            if (!seen.add(id)) continue
            val gallons = if (tank.isNull("gallons") || !tank.has("gallons")) null else tank.getDouble("gallons")
            val miles = tank.getDouble("miles")
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(id))
            val cells = listOf(
                id.toString(), date, miles.toString(), gallons?.toString() ?: "",
                gallons?.takeIf { it > 0.0 }?.let { (miles / it).toString() } ?: "",
                tank.getLong("seconds").toString(),
                if (tank.has("beforeLevel")) tank.getDouble("beforeLevel").toString() else "",
                if (tank.has("afterLevel")) tank.getDouble("afterLevel").toString() else ""
            )
            csv.append(cells.joinToString(",")).append("\r\n")
        }
        val stream = historyFile.startWrite()
        try {
            stream.write(csv.toString().toByteArray(Charsets.UTF_8))
            historyFile.finishWrite(stream)
            // Public copy for Files/Excel; private CSV and JSON remain recovery sources.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    PublicDocuments.write(app, PublicDocuments.ROOT, "Fuel-Up-History.csv",
                        "text/csv", csv.toString().toByteArray(Charsets.UTF_8))
                } catch (e: Exception) {
                    DiagnosticLog.event("FUEL_PUBLIC_HISTORY_ERROR", e.javaClass.simpleName)
                }
            }
        } catch (e: Exception) {
            historyFile.failWrite(stream)
            throw e
        }
    }

    private fun save() {
        val bytes = state.toString(2).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
            try {
                PublicDocuments.write(app, PublicDocuments.ROOT, "Fuel-Tracker.json",
                    "application/json", bytes)
            } catch (e: Exception) {
                DiagnosticLog.event("FUEL_PUBLIC_TRACKER_ERROR", e.javaClass.simpleName)
            }
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized fun start() {
        if (task != null) return
        task = executor.scheduleWithFixedDelay({
            try { sample() }
            catch (e: Exception) {
                DiagnosticLog.event("FUEL_TRACKER_ERROR", e.javaClass.simpleName + " " + e.message)
            }
        }, 0L, 2L, TimeUnit.SECONDS)
    }

    @Synchronized fun stop() {
        task?.cancel(false)
        task = null
        // Persist the latest confirmed baseline and tank counters on normal stop.
        save()
        lastSampleMs = 0L
        candidate = null
        candidateCount = 0
        candidateBaseline = null
        warnedNoFuelTimestamp = false
    }

    @Synchronized fun close() {
        stop()
        executor.shutdown()
    }

    @Synchronized fun pendingSummary(): Summary? = latest ?: run {
        val obj = state.optJSONObject("pending") ?: return@run null
        summaryFromJson(obj).also { latest = it }
    }

    // Manual preview uses current accumulated data; never alters tank history or pending state.
    @Synchronized fun previewSummary(): Summary {
        val gallons = if (state.optBoolean("fuelRateValid", true) &&
            state.optBoolean("fuelRateSeen", false)) state.optDouble("gallons") else null
        val miles = state.optDouble("miles")
        return Summary(-1L, miles, gallons, state.optLong("seconds"),
            gallons?.takeIf { it > 0.0 }?.let { miles / it })
    }

    @Synchronized fun acknowledge(id: Long) {
        if (state.optJSONObject("pending")?.optLong("id") == id) {
            state.remove("pending")
            save()
            latest = null
            DiagnosticLog.event("FUEL_TANK_ACKNOWLEDGED", "id=$id")
        }
    }

    private fun summaryFromJson(o: JSONObject): Summary {
        val gallons = if (o.isNull("gallons") || !o.has("gallons")) null else o.getDouble("gallons")
        return Summary(o.getLong("id"), o.getDouble("miles"), gallons,
            o.getLong("seconds"), gallons?.takeIf { it > 0.0 }?.let { o.getDouble("miles") / it })
    }

    private fun read(ts: ITorqueService, pid: String): Double? = try {
        val v = ts.getPIDValuesAsDouble(arrayOf(pid)).firstOrNull()
        v?.takeIf { it.isFinite() && it >= 0.0 }
    } catch (_: Exception) { null }

    @Synchronized private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val ts = service.torqueService ?: run {
            lastSampleMs = 0L
            return
        }
        if (!ts.isConnectedToECU) {
            lastSampleMs = 0L
            return
        }
        val level = read(ts, "2f,0")?.takeIf { it <= 100.0 }
            ?: run { lastSampleMs = 0L; return }
        val speed = read(ts, "0d,0")?.takeIf { it <= 250.0 }
        val rate = read(ts, "5e,0")?.takeIf { it <= 400.0 }
        val delta = if (lastSampleMs == 0L) 0.0
            else ((now - lastSampleMs) / 1000.0).coerceIn(0.0, 4.0)
        lastSampleMs = now

        // Only count connected, continuous readings; no extrapolation over gaps.
        if (delta > 0 && speed != null) {
            state.put("miles", state.optDouble("miles") + speed * delta / 3600.0 * 0.621371192)
            if (speed > 0.5) state.put("seconds", state.optLong("seconds") + delta.toLong())
            if (rate != null) {
                if (rate > 0.0) state.put("fuelRateSeen", true)
                state.put("gallons", state.optDouble("gallons") + rate * delta / 3600.0 / 3.785411784)
            } else state.put("fuelRateValid", false)
        }

        // Refill detection is independent of RPM and survives application restarts.
        // A source timestamp must advance; five cached API returns are not five samples.
        val fuelQuality = PidValidator.inspect(ts, "2f,0", level, 0.0, 100.0, "fuel")
        if (fuelQuality.updateTime <= 0L && !warnedNoFuelTimestamp) {
            warnedNoFuelTimestamp = true
            DiagnosticLog.event("FUEL_TIMESTAMP_UNAVAILABLE",
                "Cannot confirm distinct ECU fuel-level updates")
        }
        if (fuelQuality.quality == PidValidator.Quality.VALID) {
            val persisted = state.optDouble("fuelBaseline", Double.NaN)
            val baseline = if (persisted.isFinite() && persisted in 0.0..100.0)
                persisted else null
            if (baseline == null) {
                // Do not use an isolated zero as a new baseline.
                if (level > 0.0) {
                    state.put("fuelBaseline", level)
                    save()
                }
            } else if (level >= baseline + 25.0) {
                if (candidate == null || abs(level - candidate!!) > 3.0 ||
                    candidateBaseline != baseline) {
                    candidate = level
                    candidateBaseline = baseline
                    candidateCount = 1
                } else candidateCount++
                if (candidateCount >= 5) {
                    DiagnosticLog.event("FUEL_REFILL_ACCEPTED",
                        "before=$baseline after=$level samples=$candidateCount")
                    onRefill(level, baseline)
                    candidate = null
                    candidateBaseline = null
                    candidateCount = 0
                }
            } else {
                candidate = null
                candidateBaseline = null
                candidateCount = 0
                // Baseline tracks consumption, never ratchets upward during filling.
                if (level < baseline && level > 0.0) state.put("fuelBaseline", level)
            }
        }
        // Avoid a write on every 2s poll; save approximately every 30 seconds.
        if (now - lastPersistMs > 30_000L) {
            save()
            lastPersistMs = now
        }
    }

    private var lastPersistMs = 0L

    private fun onRefill(after: Double, before: Double) {
        // Commit the completed tank and new baseline in one atomic replacement.
        // If the write fails, leave the in-memory authoritative state unchanged.
        val next = JSONObject(state.toString())
        next.put("lowLevel", after)
        next.put("fuelBaseline", after)
        if (state.optDouble("miles") < 1.0) {
            commit(next)
            return
        }
        val id = System.currentTimeMillis()
        val gallons = if (state.optBoolean("fuelRateValid", true) &&
            state.optBoolean("fuelRateSeen", false)) state.optDouble("gallons") else null
        val finished = JSONObject().put("id", id)
            .put("miles", state.optDouble("miles"))
            .put("gallons", gallons ?: JSONObject.NULL)
            .put("seconds", state.optLong("seconds"))
            .put("beforeLevel", before).put("afterLevel", after)
        next.getJSONArray("completed").put(finished)
        next.put("pending", finished)
        next.put("miles", 0.0)
        next.put("gallons", 0.0)
        next.put("seconds", 0L)
        next.put("fuelRateValid", true)
        next.put("fuelRateSeen", false)
        commit(next)
        latest = summaryFromJson(finished)
        DiagnosticLog.event("FUEL_TANK_COMPLETED",
            "id=$id miles=${latest!!.miles} gallons=${latest!!.gallons} seconds=${latest!!.seconds}")
    }

    private fun commit(next: JSONObject) {
        val bytes = next.toString(2).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        state = next
        try { writeHistory(state) }
        catch (e: Exception) {
            DiagnosticLog.event("FUEL_HISTORY_WRITE_ERROR", e.javaClass.simpleName)
            // The completed tank is safe in Fuel-Tracker.json and will be
            // recovered into the CSV at next startup.
        }
    }
}
