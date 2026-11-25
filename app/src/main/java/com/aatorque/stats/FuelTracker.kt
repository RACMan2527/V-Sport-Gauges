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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Trip accounting from Torque trip distance (ff1204), time (ff1266)
 * and cumulative fuel (ff1271, liters), after verifying Torque metadata.
 * Until the cumulative PID is identified, retain the legacy MPG-derived fallback.
 * 012F fuel level is used solely for refill detection; no 010D or 015E.
 * Cached Torque trip values are read without requesting extra ECU polls.
 */
class FuelTracker(context: Context, private var service: TorqueService) {
    data class Summary(val id: Long, val miles: Double, val gallons: Double?,
                       val seconds: Long, val mpg: Double?)
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, "Fuel-Tracker.json"))
    private val historyFile = AtomicFile(File(app.filesDir, "Fuel-Up-History.csv"))
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "V-Sport-Fuel-Tracker").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null
    private var state = load().also { migrateLegacyFuelAccounting(it); writeHistory(it) }
    private var lastSampleMs = 0L
    // Daily totals are independent of the tank and use the phone's local date.
    private fun daily(): JSONObject {
        val today = LocalDate.now().toString()
        val current = state.optJSONObject("daily")
        if (current != null && current.optString("date") == today) return current
        // Keep the Torque trip baselines when the local date changes. Torque
        // does not reset its session counters at midnight, and discarding these
        // baselines would silently lose the first post-midnight increment.
        val next = JSONObject().put("date", today).put("miles", 0.0)
            .put("seconds", 0.0).put("gallons", 0.0)
            .put("mpgMiles", 0.0)
        if (current != null) {
            listOf("tripBaselineMiles", "tripBaselineSeconds",
                "tripBaselineGallons", "tripFuelBaselineMiles").forEach { key ->
                if (current.has(key)) next.put(key, current.get(key))
            }
        }
        if (current != null) {
            next.put("rolloverFromDate", current.optString("date"))
            next.put("rolloverPreviousEpoch", current.optLong("tripSampleEpochMs", 0L))
        }
        return next.also {
            state.put("daily", it)
            DiagnosticLog.event("DAILY_TRIP_RESET", "date=$today")
            save()
        }
    }

    private fun trip3(): JSONObject = state.optJSONObject("trip3") ?: JSONObject()
        .put("miles", 0.0).put("seconds", 0.0).put("gallons", 0.0)
        .put("mpgMiles", 0.0).also { state.put("trip3", it) }

    @Synchronized fun resetTrip3() {
        state.put("trip3", JSONObject().put("miles", 0.0).put("seconds", 0.0)
            .put("gallons", 0.0).put("mpgMiles", 0.0)
            .put("startedAt", OffsetDateTime.now().toString()))
        save()
        DiagnosticLog.event("TRIP3_RESET", "confirmed")
    }

    @Synchronized fun trip3Value(id: String): Double {
        val trip = trip3()
        return when (id) {
            "VS_TRIP3_DISTANCE" -> trip.optDouble("miles") / 0.621371192
            "VS_TRIP3_TIME" -> trip.optDouble("seconds")
            "VS_TRIP3_GALLONS" -> trip.optDouble("gallons")
            "VS_TRIP3_MPG" -> trip.optDouble("gallons").takeIf { it > 0.0 }
                ?.let { trip.optDouble("miles") / it } ?: 0.0
            else -> 0.0
        }
    }

    private fun addTripIncrement(target: JSONObject, miles: Double, seconds: Double,
                                 gallons: Double, pairedMiles: Double) {
        target.put("miles", target.optDouble("miles") + miles)
        target.put("seconds", target.optDouble("seconds") + seconds)
        target.put("gallons", target.optDouble("gallons") + gallons)
        target.put("mpgMiles", target.optDouble("mpgMiles") + pairedMiles)
    }

    @Synchronized fun dailyValue(id: String): Double {
        val today = daily()
        return when (id) {
            "VS_DAILY_DISTANCE" -> today.optDouble("miles") / 0.621371192
            "VS_DAILY_TIME" -> today.optDouble("seconds")
            "VS_DAILY_GALLONS" -> today.optDouble("gallons")
            "VS_DAILY_MPG" -> {
                val gallons = today.optDouble("gallons")
                if (gallons > 0.0) today.optDouble("miles") / gallons else 0.0
            }
            else -> 0.0
        }
    }

    /**
     * Daily trip accounting is independent of tank accounting. Persist the Torque
     * cumulative baseline so reopening the app cannot count the same trip twice.
     * A fresh trip's first sample is included only when its counters are near zero;
     * otherwise an unknown prior segment is deliberately not backfilled.
     */
    // Monotonic clock is used only for validating increments within this process.
    // A restart never authorizes an unbounded catch-up increment.
    private var dailyLastAcceptedMs = 0L
    private var dailyResetCandidate = 0
    private var lastTripCheckpointMs = 0L

    private fun recordDailyTrip(miles: Double, seconds: Double, currentGallons: Double?) {
        if (!miles.isFinite() || !seconds.isFinite() || miles < 0.0 || seconds < 0.0) return
        val now = SystemClock.elapsedRealtime()
        val today = daily()
        var preMidnightMiles = 0.0
        var preMidnightSeconds = 0.0
        var preMidnightGallons = 0.0
        var preMidnightPairedMiles = 0.0
        // Attribute only the post-midnight portion of a continuous Torque trip.
        // Do not extrapolate across missing samples, process restarts or resets.
        if (today.has("rolloverFromDate")) {
            val previousEpoch = today.optLong("rolloverPreviousEpoch", 0L)
            val currentEpoch = System.currentTimeMillis()
            val midnightEpoch = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            val oldMiles = today.optDouble("tripBaselineMiles", Double.NaN)
            val oldSeconds = today.optDouble("tripBaselineSeconds", Double.NaN)
            val oldGallons = today.optDouble("tripBaselineGallons", Double.NaN)
            val oldFuelMiles = today.optDouble("tripFuelBaselineMiles", Double.NaN)
            val canSplit = today.optString("rolloverFromDate") ==
                LocalDate.now().minusDays(1).toString() &&
                previousEpoch >= midnightEpoch - 120_000L &&
                previousEpoch < midnightEpoch &&
                currentEpoch > midnightEpoch &&
                currentEpoch - previousEpoch <= 120_000L &&
                dailyLastAcceptedMs != 0L &&
                oldMiles.isFinite() && oldSeconds.isFinite() &&
                miles >= oldMiles && seconds >= oldSeconds
            if (canSplit) {
                val fraction = (midnightEpoch - previousEpoch).toDouble() /
                    (currentEpoch - previousEpoch).toDouble()
                preMidnightMiles = (miles - oldMiles) * fraction
                preMidnightSeconds = (seconds - oldSeconds) * fraction
                today.put("tripBaselineMiles", oldMiles + preMidnightMiles)
                today.put("tripBaselineSeconds", oldSeconds + (seconds - oldSeconds) * fraction)
                if (currentGallons != null && oldGallons.isFinite() &&
                    currentGallons >= oldGallons) {
                    today.put("tripBaselineGallons",
                        oldGallons + (currentGallons - oldGallons) * fraction)
                    preMidnightGallons = (currentGallons - oldGallons) * fraction
                    preMidnightPairedMiles = (miles - oldMiles) * fraction
                    if (oldFuelMiles.isFinite()) {
                        today.put("tripFuelBaselineMiles",
                            oldFuelMiles + (miles - oldFuelMiles) * fraction)
                    }
                } else {
                    today.remove("tripBaselineGallons")
                    today.remove("tripFuelBaselineMiles")
                }
                DiagnosticLog.event("DAILY_MIDNIGHT_SPLIT", "fraction=$fraction")
            } else {
                today.put("tripBaselineMiles", miles)
                today.put("tripBaselineSeconds", seconds)
                if (currentGallons != null) {
                    today.put("tripBaselineGallons", currentGallons)
                    today.put("tripFuelBaselineMiles", miles)
                } else {
                    today.remove("tripBaselineGallons")
                    today.remove("tripFuelBaselineMiles")
                }
                DiagnosticLog.event("DAILY_MIDNIGHT_REBASE", "unverified interval")
            }
            today.remove("rolloverFromDate")
            today.remove("rolloverPreviousEpoch")
        }
        val previousMiles = today.optDouble("tripBaselineMiles", Double.NaN)
        val previousSeconds = today.optDouble("tripBaselineSeconds", Double.NaN)
        val previousGallons = today.optDouble("tripBaselineGallons", Double.NaN)
        // Keep the highest confirmed cumulative fuel within a Torque trip.
        // Recomputed trip MPG can make miles/MPG fall temporarily; counting
        // its later recovery again would inflate daily fuel consumption.
        val previousFuelMiles = today.optDouble("tripFuelBaselineMiles", Double.NaN)
        val baselineKnown = previousMiles.isFinite() && previousSeconds.isFinite()
        val reset = baselineKnown && (miles + 0.01 < previousMiles ||
            seconds + 0.5 < previousSeconds)
        if (reset) {
            // One backward snapshot may be a stale Torque cache. Confirm before rebasing.
            dailyResetCandidate++
            if (dailyResetCandidate < 2) {
                DiagnosticLog.event("DAILY_TRIP_SAMPLE_REJECTED", "reason=possible_reset")
                return
            }
        } else {
            dailyResetCandidate = 0
        }
        val first = !baselineKnown || reset || dailyLastAcceptedMs == 0L
        // On a confirmed Torque reset, the new counters are already part of
        // today's driving. Count only a demonstrably fresh, short first segment.
        val includeFirst = reset && seconds <= 120.0 && miles <= 2.0 &&
            dailyLastAcceptedMs != 0L
        val milesDelta = if (includeFirst) miles else if (first) 0.0 else miles - previousMiles
        val secondsDelta = if (includeFirst) seconds else if (first) 0.0 else seconds - previousSeconds
        val elapsedSeconds = if (dailyLastAcceptedMs == 0L) 0.0
            else (now - dailyLastAcceptedMs).coerceAtLeast(0L) / 1000.0
        // Permit a generous 180 mph and 30 seconds of polling lag; never
        // allow a single bad Torque snapshot to inflate persistent daily totals.
        if (!first && (milesDelta < 0.0 || secondsDelta < 0.0 ||
                milesDelta > (elapsedSeconds + 30.0) * 180.0 / 3600.0 ||
                secondsDelta > elapsedSeconds + 30.0)) {
            DiagnosticLog.event("DAILY_TRIP_SAMPLE_REJECTED",
                "reason=implausible_delta miles=$milesDelta seconds=$secondsDelta elapsed=$elapsedSeconds")
            return
        }
        var fuelIncrement = 0.0
        var pairedIncrement = 0.0
        if (currentGallons != null && currentGallons.isFinite()) {
            // Reset or first observation establishes a baseline, never an
            // unverified catch-up. On confirmed short trip resets include the
            // short first segment just as the distance/time counters do.
            if (includeFirst) {
                fuelIncrement = currentGallons
                pairedIncrement = miles
                today.put("tripBaselineGallons", currentGallons)
                today.put("tripFuelBaselineMiles", miles)
            } else if (first || !previousGallons.isFinite()) {
                today.put("tripBaselineGallons", currentGallons)
                today.put("tripFuelBaselineMiles", miles)
            } else {
                val gallonsDelta = currentGallons - previousGallons
                if (gallonsDelta > 0.0) {
                    val pairedMiles = if (previousFuelMiles.isFinite())
                        (miles - previousFuelMiles).coerceAtLeast(0.0) else milesDelta.coerceAtLeast(0.0)
                    fuelIncrement = gallonsDelta
                    pairedIncrement = pairedMiles
                    today.put("tripBaselineGallons", currentGallons)
                    today.put("tripFuelBaselineMiles", miles)
                } else if (gallonsDelta < 0.0) {
                    // Preserve the high-water mark so a rebound is not
                    // mistaken for newly consumed fuel.
                    noteFuelHold(gallonsDelta, currentGallons, previousGallons, now)
                }
            }
        } else if (reset) {
            // Never carry a previous session's fuel baseline into a reset.
            today.remove("tripBaselineGallons")
            today.remove("tripFuelBaselineMiles")
        }
        // A temporarily missing fuel value within the same trip is not a reset.
        addTripIncrement(today, milesDelta, secondsDelta, fuelIncrement, pairedIncrement)
        val trip3State = trip3()
        // Timestamp the first accepted accumulation; preserve an existing seeded start.
        if (!trip3State.has("startedAt") && trip3State.optDouble("miles") == 0.0 &&
            trip3State.optDouble("seconds") == 0.0 &&
            (milesDelta + preMidnightMiles > 0.0 || secondsDelta + preMidnightSeconds > 0.0)) {
            trip3State.put("startedAt", OffsetDateTime.now().toString())
        }
        addTripIncrement(trip3State, milesDelta + preMidnightMiles,
            secondsDelta + preMidnightSeconds, fuelIncrement + preMidnightGallons,
            pairedIncrement + preMidnightPairedMiles)
        today.put("tripBaselineMiles", miles)
        today.put("tripBaselineSeconds", seconds)
        today.put("tripSampleEpochMs", System.currentTimeMillis())
        dailyLastAcceptedMs = now
        if (lastTripCheckpointMs == 0L || now - lastTripCheckpointMs >= 60_000L) {
            save()
            lastTripCheckpointMs = now
        }
        if (reset) {
            dailyResetCandidate = 0
            DiagnosticLog.event("DAILY_TRIP_RESET_DETECTED", "confirmed trip counters restarted")
        }
    }

    private var fuelMetadataVerified = false
    private var lastFuelMetadataCheckMs: Long? = null
    private var lastFuelSourceReportMs: Long? = null
    private var holdCount = 0
    private var holdStartedMs = 0L
    private var holdWorst = 0.0
    private var tankResetCandidate = 0

    private fun noteFuelHold(delta: Double, current: Double, baseline: Double, now: Long) {
        if (holdCount == 0) holdStartedMs = now
        holdCount++
        holdWorst = minOf(holdWorst, delta)
        if (holdCount == 1 || now - holdStartedMs >= 60_000L) {
            DiagnosticLog.event("DAILY_TRIP_FUEL_HOLD",
                "count=$holdCount durationMs=${now - holdStartedMs} worstDelta=$holdWorst current=$current baseline=$baseline source=${state.optString("tripFuelSource")}")
            if (holdCount > 1) {
                holdCount = 0
                holdWorst = 0.0
            }
        }
    }

    // The PID ID differs in older Torque inventories. Verify the actual API's
    // long name and raw unit before interpreting any reading as liters.
    private fun useCumulativeFuel(ts: ITorqueService, now: Long): Boolean {
        if (fuelMetadataVerified) return true
        val last = lastFuelMetadataCheckMs
        if (last != null && now - last < 30_000L) return false
        lastFuelMetadataCheckMs = now
        val fields = try {
            ts.getPIDInformation(arrayOf("ff1271,0")).firstOrNull()?.split(",")
        } catch (_: Exception) { null }
        val name = fields?.getOrNull(0)?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
        val unit = fields?.getOrNull(2)?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
        fuelMetadataVerified = name.contains("fuel") &&
            (name.contains("used") || name.contains("consum")) &&
            (name.contains("trip") || name.contains("session")) &&
            unit in setOf("l", "liter", "liters", "litre", "litres")
        DiagnosticLog.event("FUEL_CUMULATIVE_PID_CHECK",
            "pid=ff1271,0 verified=$fuelMetadataVerified name=$name unit=$unit")
        return fuelMetadataVerified
    }

    private fun selectFuelSource(direct: Boolean) {
        val source = if (direct) "ff1271_liters" else "ff1205_derived"
        // Old files without a source marker contain the legacy derived baseline.
        val previous = state.optString("tripFuelSource", "ff1205_derived")
        if (source == previous) return
        daily().remove("tripBaselineGallons")
        daily().remove("tripFuelBaselineMiles")
        previousTripGallons = null
        state.put("tripFuelSource", source)
        DiagnosticLog.event("FUEL_SOURCE_CHANGE", "old=$previous new=$source")
        save()
    }

    private var previousTripMiles: Double? = null
    private var previousTripGallons: Double? = null
    private var previousTripSeconds: Double? = null
    private var candidate: Double? = null
    private var candidateCount = 0
    private var candidateBaseline: Double? = null
    private var candidateStableSinceMs = 0L
    private var candidateLastFreshMs = 0L
    private var observedEngineOff = false
    private var engineStartedMs = 0L
    private var lastRpmToken = 0L
    private val refillStabilityMs = 60_000L
    private val justStartedWindowMs = 30_000L
    private val refillRiseTolerance = 0.5
    private var warnedNoFuelTimestamp = false
    @Volatile private var latest: Summary? = null

    private fun emptyState(): JSONObject = JSONObject().put("schema", 3)
        .put("miles", 0.0).put("gallons", 0.0).put("seconds", 0L)
        .put("fuelRateValid", true).put("fuelRateSeen", false)
        .put("completed", JSONArray())

    private fun load(): JSONObject = try {
        val bytes = try {
            file.openRead().use { it.readBytes() }.also {
                DiagnosticLog.event("FUEL_TRACKER_SOURCE", "private")
            }
        } catch (_: FileNotFoundException) {
            DiagnosticLog.event("FUEL_TRACKER_PRIVATE_MISSING")
            if (!PublicDocuments.linkedFuelDocument(app, "Fuel-Tracker.json")) {
                DiagnosticLog.event("FUEL_TRACKER_LINK_MISSING")
                throw IllegalStateException("Select existing Fuel-Tracker.json in phone Settings")
            }
            PublicDocuments.readFuelDocument(app, "Fuel-Tracker.json")
                ?.also { DiagnosticLog.event("FUEL_TRACKER_SOURCE", "linked_documents") }
                ?: throw IllegalStateException("Linked Fuel-Tracker.json cannot be read")
        }
        JSONObject(String(bytes, Charsets.UTF_8)).also {
            require(it.has("miles") && it.has("seconds") && it.optJSONArray("completed") != null) {
                "Incomplete fuel tracker state"
            }
        }
    } catch (e: Exception) {
        // Never replace a damaged or unreadable tank with an empty one.
        DiagnosticLog.event("FUEL_TRACKER_LOAD_ERROR", e.javaClass.simpleName + " " + e.message)
        throw IllegalStateException("Cannot recover Fuel-Tracker.json; existing data preserved", e)
    }


    /** Keep prior unverified raw-PID totals for audit, but never mix them with
     * Torque trip totals; doing so would produce a misleading tank MPG. */
    private fun migrateLegacyFuelAccounting(source: JSONObject) {
        if (source.optInt("schema", 2) >= 3) return
        source.put("legacyRawPidMiles", source.optDouble("miles"))
        source.put("legacyRawPidSeconds", source.optLong("seconds"))
        source.put("legacyRawPidGallons", source.optDouble("gallons"))
        source.put("miles", 0.0)
        source.put("seconds", 0L)
        source.put("gallons", 0.0)
        source.put("fuelRateValid", true)
        source.put("fuelRateSeen", false)
        source.put("schema", 3)
        DiagnosticLog.event("FUEL_TRACKER_MIGRATED", "Legacy raw-PID totals preserved separately")
        val bytes = source.toString(2).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
            try { PublicDocuments.write(app, PublicDocuments.ROOT, "Fuel-Tracker.json", "application/json", bytes) }
            catch (e: Exception) { DiagnosticLog.event("FUEL_PUBLIC_TRACKER_ERROR", e.javaClass.simpleName) }
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
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
                val actual = PublicDocuments.readFuelDocument(app, "Fuel-Tracker.json")
                if (actual == null || !actual.contentEquals(bytes)) {
                    DiagnosticLog.event("FUEL_PUBLIC_VERIFY_ERROR",
                        "readback_mismatch expected=${bytes.size} actual=${actual?.size ?: -1}")
                }
            } catch (e: Exception) {
                DiagnosticLog.event("FUEL_PUBLIC_SAVE_ERROR",
                    e.javaClass.simpleName + " " + (e.message ?: ""))
            }
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized fun useTorqueService(source: TorqueService) { service = source }

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
        previousTripMiles = null
        previousTripGallons = null
        previousTripSeconds = null
        tankResetCandidate = 0
        candidate = null
        candidateCount = 0
        candidateBaseline = null
        candidateStableSinceMs = 0L
        candidateLastFreshMs = 0L
        observedEngineOff = false
        engineStartedMs = 0L
        lastRpmToken = 0L
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
            val queue = state.optJSONArray("pendingQueue")
            if (queue != null && queue.length() > 0) {
                val remaining = JSONArray()
                for (i in 0 until queue.length()) if (queue.getJSONObject(i).optLong("id") != id) remaining.put(queue.getJSONObject(i))
                state.put("pendingQueue", remaining)
                if (remaining.length() > 0) state.put("pending", remaining.getJSONObject(0)) else state.remove("pending")
            } else state.remove("pending")
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
        // These are the same raw Torque trip values displayed on the dashboards.
        daily() // Advance the local date before consuming the next sample.
        val tripKm = read(ts, "ff1204,0")
        val tripSeconds = read(ts, "ff1266,0")
        val tripMiles = tripKm?.times(0.621371192)
        val direct = useCumulativeFuel(ts, now)
        // If the direct source was already established in a previous process,
        // do not fall back and discard its baseline on a temporary metadata error.
        val awaitingDirect = !direct && state.optString("tripFuelSource") == "ff1271_liters"
        if (!awaitingDirect) selectFuelSource(direct)
        val tripGallons = if (awaitingDirect) null else if (direct) {
            read(ts, "ff1271,0")?.takeIf { it < 1_000_000.0 }?.div(3.785411784)
        } else {
            val tripMpg = read(ts, "ff1205,0")?.takeIf { it > 0.0 && it < 1000.0 }
            if (tripMiles != null && tripMpg != null) tripMiles / tripMpg else null
        }

        if (tripMiles != null && tripSeconds != null) {
            recordDailyTrip(tripMiles, tripSeconds, tripGallons)
        }

        if (tripMiles != null && tripSeconds != null) {
            val oldMiles = previousTripMiles
            val oldSeconds = previousTripSeconds
            val oldGallons = previousTripGallons
            val reset = (oldMiles != null && tripMiles + 0.01 < oldMiles) ||
                (oldSeconds != null && tripSeconds + 0.5 < oldSeconds)
            if (reset) tankResetCandidate++ else tankResetCandidate = 0
            if (reset && tankResetCandidate < 2) {
                // Do not rebase tank accounting on a single stale snapshot.
            } else {
                if (reset) {
                    previousTripGallons = null
                    tankResetCandidate = 0
                    // A confirmed fresh short trip contributes even at zero miles.
                    if (tripSeconds <= 120.0 && tripMiles <= 2.0) {
                        state.put("miles", state.optDouble("miles") + tripMiles)
                        state.put("seconds", state.optLong("seconds") + tripSeconds.toLong())
                        if (tripGallons != null) {
                            state.put("gallons", state.optDouble("gallons") + tripGallons)
                            state.put("fuelRateSeen", true)
                        }
                    }
                    DiagnosticLog.event("FUEL_TRIP_RESET", "Torque trip distance/time reset")
                } else if (oldMiles != null && oldSeconds != null) {
                    val milesDelta = (tripMiles - oldMiles).coerceAtLeast(0.0)
                    val secondsDelta = (tripSeconds - oldSeconds).coerceAtLeast(0.0)
                    state.put("miles", state.optDouble("miles") + milesDelta)
                    state.put("seconds", state.optLong("seconds") + secondsDelta.toLong())
                    if (tripGallons != null && oldGallons != null) {
                        val gallonsDelta = tripGallons - oldGallons
                        if (gallonsDelta >= 0.0) {
                            state.put("gallons", state.optDouble("gallons") + gallonsDelta)
                            state.put("fuelRateSeen", true)
                        } else if (gallonsDelta < -0.02) {
                            DiagnosticLog.event("FUEL_TRIP_GALLONS_DECREASE", "delta=$gallonsDelta")
                        }
                    }
                }
                previousTripMiles = tripMiles
                if (tripGallons != null &&
                    (previousTripGallons == null || tripGallons >= previousTripGallons!!)) {
                    previousTripGallons = tripGallons
                }
                previousTripSeconds = tripSeconds
            }
        }
        lastSampleMs = now
        val lastReport = lastFuelSourceReportMs
        if (direct && (lastReport == null || now - lastReport >= 60_000L)) {
            lastFuelSourceReportMs = now
            DiagnosticLog.event("FUEL_CUMULATIVE_SAMPLE",
                "sessionMiles=$tripMiles sessionSeconds=$tripSeconds sessionGallons=$tripGallons dailyGallons=${daily().optDouble("gallons")} trip3Gallons=${trip3().optDouble("gallons")} tankGallons=${state.optDouble("gallons")}")
        }

        // Level availability gates only refill detection, not any trip totals.
        val level = read(ts, "2f,0")?.takeIf { it <= 100.0 }
            ?: run {
                if (now - lastPersistMs > 30_000L) {
                    save()
                    lastPersistMs = now
                }
                return
            }

        // Only an observed engine-off -> running transition grants the immediate
        // post-start refill path. App/AA reconnects never count as engine starts.
        val rpm = read(ts, "0c,0")?.takeIf { it <= 7000.0 }
        if (rpm != null) {
            val rpmQuality = PidValidator.inspect(ts, "0c,0", rpm, 0.0, 7000.0, "fuel_rpm")
            if (rpmQuality.quality == PidValidator.Quality.VALID &&
                rpmQuality.updateTime != lastRpmToken) {
                lastRpmToken = rpmQuality.updateTime
                if (rpm < 300.0) {
                    observedEngineOff = true
                    engineStartedMs = 0L
                } else if (observedEngineOff) {
                    engineStartedMs = now
                    observedEngineOff = false
                    DiagnosticLog.event("FUEL_ENGINE_START_OBSERVED")
                }
            }
        }
        // Fuel readings must represent distinct ECU updates, not cached API returns.
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
                if (candidate == null || candidateBaseline != baseline) {
                    candidate = level
                    candidateBaseline = baseline
                    candidateCount = 1
                    candidateStableSinceMs = now
                    candidateLastFreshMs = now
                    DiagnosticLog.event("FUEL_REFILL_CANDIDATE",
                        "before=$baseline level=$level")
                } else {
                    candidateCount++
                    // A continued rise means the pump may still be filling.
                    // Only a genuinely higher ECU reading restarts stability.
                    if (level > candidate!! + refillRiseTolerance) {
                        candidate = level
                        candidateStableSinceMs = now
                    } else if (level > candidate!!) {
                        candidate = level
                    }
                    candidateLastFreshMs = now
                }
                val justStarted = engineStartedMs > 0L &&
                    now - engineStartedMs in 0L..justStartedWindowMs
                val stable = candidateStableSinceMs > 0L &&
                    now - candidateStableSinceMs >= refillStabilityMs
                if (candidateCount >= 5 && (justStarted || stable)) {
                    val finalLevel = candidate!!
                    DiagnosticLog.event("FUEL_REFILL_ACCEPTED",
                        "before=$baseline after=$finalLevel samples=$candidateCount " +
                            "mode=${if (justStarted) "engine_start" else "stable_60s"}")
                    onRefill(finalLevel, baseline)
                    candidate = null
                    candidateBaseline = null
                    candidateCount = 0
                    candidateStableSinceMs = 0L
                    candidateLastFreshMs = 0L
                }
            } else {
                candidate = null
                candidateBaseline = null
                candidateCount = 0
                candidateStableSinceMs = 0L
                candidateLastFreshMs = 0L
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
        val pendingQueue = next.optJSONArray("pendingQueue") ?: JSONArray().also { queue ->
            next.optJSONObject("pending")?.let { queue.put(it) }
        }
        pendingQueue.put(finished)
        next.put("pendingQueue", pendingQueue)
        next.put("pending", pendingQueue.getJSONObject(0))
        next.put("miles", 0.0)
        next.put("gallons", 0.0)
        next.put("seconds", 0L)
        next.put("fuelRateValid", true)
        next.put("fuelRateSeen", false)
        commit(next)
        latest = summaryFromJson(next.getJSONObject("pending"))
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
