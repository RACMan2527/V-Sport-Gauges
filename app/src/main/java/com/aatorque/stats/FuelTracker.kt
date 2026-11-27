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
            .put("startedAt", OffsetDateTime.now().toString())
            .put("fuelCutoffEpochMs", System.currentTimeMillis()))
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
                ?.let { trip.optDouble("mpgMiles", trip.optDouble("miles")) / it } ?: 0.0
            else -> 0.0
        }
    }

    @Synchronized fun dailyValue(id: String): Double {
        val today = daily()
        return when (id) {
            "VS_DAILY_DISTANCE" -> today.optDouble("miles") / 0.621371192
            "VS_DAILY_TIME" -> today.optDouble("seconds")
            "VS_DAILY_GALLONS" -> today.optDouble("gallons")
            "VS_DAILY_MPG" -> {
                val gallons = today.optDouble("gallons")
                if (gallons > 0.0) today.optDouble("mpgMiles", today.optDouble("miles")) / gallons else 0.0
            }
            else -> 0.0
        }
    }

    // One persisted Torque-session baseline is shared by Daily, Trip 3 and Tank.
    // The existing daily keys are mirrored for compatibility with older exports.
    private data class TripReading(val value: Double, val token: Long)
    private data class ObservedCounter(val reading: TripReading, val changedAt: Long)
    private val observedCounters = mutableMapOf<String, ObservedCounter>()
    private var resetCandidate: Pair<Long, Long>? = null
    private var lastTripCheckpointMs = 0L
    private val maxFuelGallonsPerHour = 80.0
    private val tripCounterFreshnessMs = 15_000L
    private val maxSplitMs = 120_000L

    private fun accounting(): JSONObject {
        state.optJSONObject("tripAccounting")?.let { return it }
        val legacy = state.optJSONObject("daily")
        val result = JSONObject()
        if (legacy != null) {
            for (key in listOf("tripBaselineMiles", "tripBaselineSeconds",
                "tripBaselineGallons", "tripFuelBaselineMiles", "tripSampleEpochMs")) {
                if (legacy.has(key)) result.put(key, legacy.get(key))
            }
            if (legacy.has("tripBaselineGallons")) {
                result.put("fuelBaselineSeconds", legacy.optDouble("tripBaselineSeconds", 0.0))
                result.put("fuelBaselineEpochMs", legacy.optLong("tripSampleEpochMs", 0L))
            }
        }
        // Existing totals and completed tanks remain intact.
        if (!state.has("mpgMiles")) state.put("mpgMiles", state.optDouble("miles", 0.0))
        state.put("tripAccounting", result)
        return result
    }

    private fun readTripCounter(ts: ITorqueService, pid: String, maximum: Double,
                                now: Long, wall: Long): TripReading? = try {
        val value = read(ts, pid)
        val token = ts.getPIDUpdateTime(arrayOf(pid)).firstOrNull() ?: 0L
        val previous = observedCounters[pid]
        if (value == null || value > maximum || token <= 0L ||
            (token >= 1_000_000_000_000L && (wall - token > tripCounterFreshnessMs || token - wall > 5_000L))) {
            null
        } else if (previous != null && previous.reading.token == token) {
            // Unchanged cache may supply a stationary distance, but cannot
            // authenticate a changed value or indefinitely extend freshness.
            previous.reading.takeIf {
                it.value == value && now - previous.changedAt <= tripCounterFreshnessMs
            }
        } else {
            TripReading(value, token).also { observedCounters[pid] = ObservedCounter(it, now) }
        }
    } catch (_: Exception) { null }

    private fun allowedFuel(seconds: Double): Double =
        maxFuelGallonsPerHour * (seconds.coerceAtLeast(0.0) + 5.0) / 3600.0 + 0.005

    private fun clearFuelAnchor(base: JSONObject) {
        for (key in listOf("tripBaselineGallons", "tripFuelBaselineMiles",
            "fuelBaselineSeconds", "fuelBaselineEpochMs", "fuelToken")) base.remove(key)
    }

    private fun clearPendingFuel(scope: JSONObject) {
        val pending = scope.optDouble("pendingFuelMiles", 0.0)
        if (pending > 0.0) {
            scope.put("unmatchedFuelMiles", scope.optDouble("unmatchedFuelMiles", 0.0) + pending)
        }
        scope.remove("pendingFuelMiles")
    }

    private fun clearPendingFuelAll(today: JSONObject) {
        clearPendingFuel(today)
        clearPendingFuel(trip3())
        clearPendingFuel(state)
    }

    private fun setFuelAnchor(base: JSONObject, gallons: Double, miles: Double,
                              seconds: Double, epoch: Long, token: Long) {
        base.put("tripBaselineGallons", gallons)
        base.put("tripFuelBaselineMiles", miles)
        base.put("fuelBaselineSeconds", seconds)
        base.put("fuelBaselineEpochMs", epoch)
        base.put("fuelToken", token)
    }

    // Only short, continuous intervals may be apportioned across a reset or
    // midnight. For an ambiguous longer crossing, omit that scope's increment.
    private fun fractionAfter(cutoff: Long, from: Long, to: Long, continuous: Boolean): Double {
        if (cutoff <= from || cutoff <= 0L) return 1.0
        if (cutoff >= to || from <= 0L || !continuous || to - from > maxSplitMs) return 0.0
        return (to - cutoff).toDouble() / (to - from).toDouble()
    }

    private fun distribute(scope: JSONObject, miles: Double, seconds: Double,
                           gallons: Double?, fuelFraction: Double, seedOnly: Boolean) {
        val priorMiles = scope.optDouble("miles", 0.0)
        if (!scope.has("mpgMiles")) scope.put("mpgMiles", priorMiles)
        val pending = scope.optDouble("pendingFuelMiles", 0.0) + miles
        scope.put("miles", priorMiles + miles)
        scope.put("seconds", scope.optDouble("seconds", 0.0) + seconds)
        scope.put("pendingFuelMiles", pending)
        if (seedOnly || (gallons != null && fuelFraction <= 0.0)) {
            // No defensible fuel allocation for the pending distance.
            clearPendingFuel(scope)
        } else if (gallons != null) {
            scope.put("gallons", scope.optDouble("gallons", 0.0) + gallons * fuelFraction)
            scope.put("mpgMiles", scope.optDouble("mpgMiles", 0.0) + pending)
            scope.remove("pendingFuelMiles")
            if (scope === state) scope.put("fuelRateSeen", true)
        }
    }

    private fun recordTrip(distance: TripReading, time: TripReading, fuel: TripReading?,
                           wall: Long, now: Long) {
        val miles = distance.value
        val seconds = time.value
        val today = daily()
        val roadTrip = trip3()
        val base = accounting()
        val oldMiles = base.optDouble("tripBaselineMiles", Double.NaN)
        val oldSeconds = base.optDouble("tripBaselineSeconds", Double.NaN)
        val previousEpoch = base.optLong("tripSampleEpochMs", 0L)
        val known = oldMiles.isFinite() && oldSeconds.isFinite() && previousEpoch > 0L
        val elapsed = (wall - previousEpoch).coerceAtLeast(0L) / 1000.0
        val reset = known && (miles + 0.01 < oldMiles || seconds + 0.5 < oldSeconds)
        if (reset) {
            val sampleKey = distance.token to time.token
            val candidate = resetCandidate
            if (candidate == null) {
                resetCandidate = sampleKey
                DiagnosticLog.event("DAILY_TRIP_SAMPLE_REJECTED", "reason=possible_reset")
                return
            }
            if (candidate == sampleKey) return
        } else resetCandidate = null

        // A long gap with counters that could belong to a completely new trip
        // is ambiguous. Rebase rather than count an unknown trip twice.
        val ambiguous = known && !reset && elapsed > 120.0 && seconds <= elapsed + 30.0
        val first = !known || ambiguous || wall < previousEpoch
        val includeFirst = reset && seconds <= 120.0 && miles <= 2.0 &&
            seconds <= elapsed + 30.0
        val milesDelta = if (includeFirst) miles else if (first || reset) 0.0 else (miles - oldMiles).coerceAtLeast(0.0)
        val secondsDelta = if (includeFirst) seconds else if (first || reset) 0.0 else (seconds - oldSeconds).coerceAtLeast(0.0)
        if (!first && !reset && (milesDelta > (elapsed + 30.0) * 180.0 / 3600.0 ||
            secondsDelta > elapsed + 30.0)) {
            DiagnosticLog.event("DAILY_TRIP_SAMPLE_REJECTED",
                "reason=implausible_delta miles=$milesDelta seconds=$secondsDelta elapsed=$elapsed")
            return
        }

        var from = previousEpoch
        if (reset || first) {
            clearPendingFuelAll(today)
            clearFuelAnchor(base)
            if (includeFirst) {
                from = (wall - (seconds * 1000.0).toLong()).coerceAtMost(wall)
                // Fresh short sessions may include fuel before their first
                // accepted reading, including zero-distance remote starts.
                setFuelAnchor(base, 0.0, 0.0, 0.0, from, 0L)
            }
            if (reset) DiagnosticLog.event("DAILY_TRIP_RESET_DETECTED", "confirmed trip counters restarted includeFirst=$includeFirst")
            else if (ambiguous) DiagnosticLog.event("FUEL_TRIP_BASELINE_REBASE", "reason=ambiguous_gap elapsedSeconds=$elapsed")
        }

        val continuous = known && !first && !reset || includeFirst
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dayCutoff = if (today.has("rolloverFromDate") || from < midnight) midnight else 0L
        val dayFraction = fractionAfter(dayCutoff, from, wall, continuous)
        if (today.has("rolloverFromDate")) {
            if (dayFraction in 0.0..1.0 && dayFraction > 0.0 && dayFraction < 1.0)
                DiagnosticLog.event("DAILY_MIDNIGHT_SPLIT", "fractionBefore=${1.0 - dayFraction}")
            else if (dayFraction == 0.0)
                DiagnosticLog.event("DAILY_MIDNIGHT_REBASE", "unverified interval")
            today.remove("rolloverFromDate")
            today.remove("rolloverPreviousEpoch")
        }

        val oldGallons = base.optDouble("tripBaselineGallons", Double.NaN)
        val oldFuelEpoch = base.optLong("fuelBaselineEpochMs", 0L)
        val oldFuelSeconds = base.optDouble("fuelBaselineSeconds", 0.0)
        val oldToken = base.optLong("fuelToken", 0L)
        var acceptedFuel: Double? = null
        var seedOnly = false
        if (fuel != null && fuel.token != oldToken) {
            val gallons = fuel.value
            val delta = if (oldGallons.isFinite()) gallons - oldGallons else 0.0
            // Fuel is consumed while stationary too. Wall time protects the
            // rate check if Torque trip time temporarily stops advancing.
            val budgetSeconds = if (oldGallons.isFinite()) maxOf(
                (seconds - oldFuelSeconds).coerceAtLeast(0.0),
                if (oldFuelEpoch > 0L) (wall - oldFuelEpoch).coerceAtLeast(0L) / 1000.0 else 0.0)
                else seconds
            val fromCurrentSession = !reset || fuel.token < 1_000_000_000_000L ||
                fuel.token >= from - 5_000L
            when {
                !fromCurrentSession ||
                    ((!oldGallons.isFinite() || reset) && gallons > allowedFuel(seconds)) ||
                    delta > allowedFuel(budgetSeconds) -> {
                    noteFuelReject("implausible_fuel", now,
                        "gallons=$gallons delta=$delta seconds=$seconds budgetSeconds=$budgetSeconds")
                }
                !oldGallons.isFinite() -> {
                    // No previous value: establish the new-source baseline
                    // without retroactively assigning unknown fuel.
                    setFuelAnchor(base, gallons, miles, seconds, wall, fuel.token)
                    seedOnly = true
                }
                delta < 0.0 -> noteFuelHold(delta, gallons, oldGallons, now)
                else -> {
                    acceptedFuel = delta
                    setFuelAnchor(base, gallons, miles, seconds, wall, fuel.token)
                }
            }
        }

        val fuelFrom = if (oldFuelEpoch > 0L) oldFuelEpoch else from
        val tripCutoff = roadTrip.optLong("fuelCutoffEpochMs", 0L)
        val tankCutoff = state.optLong("fuelCutoffEpochMs", 0L)
        val tripFraction = fractionAfter(tripCutoff, from, wall, continuous)
        val tankFraction = fractionAfter(tankCutoff, from, wall, continuous)
        // Daily's fuel interval may begin earlier than the last distance sample
        // when fuel data was temporarily absent. Split only verified short gaps.
        distribute(today, milesDelta * dayFraction, secondsDelta * dayFraction, acceptedFuel,
            fractionAfter(midnight, fuelFrom, wall, continuous), seedOnly)
        if (!roadTrip.has("startedAt") && (milesDelta > 0.0 || secondsDelta > 0.0 || (acceptedFuel ?: 0.0) > 0.0))
            roadTrip.put("startedAt", OffsetDateTime.now().toString())
        distribute(roadTrip, milesDelta * tripFraction, secondsDelta * tripFraction, acceptedFuel,
            fractionAfter(tripCutoff, fuelFrom, wall, continuous), seedOnly)
        distribute(state, milesDelta * tankFraction, secondsDelta * tankFraction, acceptedFuel,
            fractionAfter(tankCutoff, fuelFrom, wall, continuous), seedOnly)

        // Tiny backwards jitter is neither a new session nor new distance.
        base.put("tripBaselineMiles", if (first || reset) miles else maxOf(miles, oldMiles))
        base.put("tripBaselineSeconds", if (first || reset) seconds else maxOf(seconds, oldSeconds))
        base.put("tripSampleEpochMs", wall)
        for (key in listOf("tripBaselineMiles", "tripBaselineSeconds", "tripBaselineGallons",
            "tripFuelBaselineMiles", "tripSampleEpochMs")) {
            if (base.has(key)) today.put(key, base.get(key)) else today.remove(key)
        }
        resetCandidate = null
        if (reset || first || lastTripCheckpointMs == 0L || now - lastTripCheckpointMs >= 60_000L) {
            save()
            lastTripCheckpointMs = now
        }
    }

    private var lastFuelRejectMs: Long? = null
    private fun noteFuelReject(reason: String, now: Long, details: String) {
        val previous = lastFuelRejectMs
        if (previous == null || now - previous >= 60_000L) {
            lastFuelRejectMs = now
            DiagnosticLog.event("FUEL_CUMULATIVE_REJECTED", "reason=$reason $details")
        }
    }

    private var fuelMetadataVerified = false
    private var lastFuelMetadataCheckMs: Long? = null
    private var lastFuelSourceReportMs: Long? = null
    private var holdCount = 0
    private var holdStartedMs = 0L
    private var holdWorst = 0.0

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
        val today = daily()
        clearFuelAnchor(accounting())
        clearPendingFuelAll(today)
        today.remove("tripBaselineGallons")
        today.remove("tripFuelBaselineMiles")
        state.put("tripFuelSource", source)
        DiagnosticLog.event("FUEL_SOURCE_CHANGE", "old=$previous new=$source")
        save()
    }

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
                gallons?.takeIf { it > 0.0 }?.let { (tank.optDouble("mpgMiles", miles) / it).toString() } ?: "",
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
        observedCounters.clear()
        resetCandidate = null
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
            gallons?.takeIf { it > 0.0 }?.let { state.optDouble("mpgMiles", miles) / it })
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
            o.getLong("seconds"), gallons?.takeIf { it > 0.0 }?.let { o.optDouble("mpgMiles", o.getDouble("miles")) / it })
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
        val wall = System.currentTimeMillis()
        daily()
        val distance = readTripCounter(ts, "ff1204,0", 1_000_000.0, now, wall)
            ?.let { TripReading(it.value * 0.621371192, it.token) }
        val time = readTripCounter(ts, "ff1266,0", 100_000_000.0, now, wall)
        val metadataVerified = useCumulativeFuel(ts, now)
        val directReading = if (metadataVerified)
            readTripCounter(ts, "ff1271,0", 1_000_000.0, now, wall)
                ?.let { TripReading(it.value / 3.785411784, it.token) }
            else null
        val establishedDirect = state.optString("tripFuelSource") == "ff1271_liters"
        // Never discard the old usable baseline solely because metadata matched.
        // Require a fresh, plausible first direct reading before switching.
        if (!establishedDirect && distance != null && time != null &&
            directReading != null && directReading.value <= allowedFuel(time.value)) {
            selectFuelSource(true)
        }
        val direct = state.optString("tripFuelSource") == "ff1271_liters"
        val lastReport = lastFuelSourceReportMs
        val compare = lastReport == null || now - lastReport >= 60_000L
        // FF1205 remains Torque-owned. When direct fuel is established, read
        // session MPG only for the periodic comparison, not each two-second poll.
        val mpg = if (!direct || compare)
            readTripCounter(ts, "ff1205,0", 1000.0, now, wall)?.takeIf { it.value > 0.0 }
            else null
        val derived = if (distance != null && mpg != null)
            TripReading(distance.value / mpg.value, mpg.token) else null
        val fuel = if (direct) directReading else derived
        if (distance != null && time != null) {
            if (fuel == null) noteFuelReject("missing_or_stale_fuel", now, "source=${state.optString("tripFuelSource", "ff1205_derived")}")
            recordTrip(distance, time, fuel, wall, now)
        } else noteFuelReject("missing_or_stale_trip_counter", now, "distance=${distance?.value} time=${time?.value}")
        lastSampleMs = now
        if (compare) {
            lastFuelSourceReportMs = now
            val directGallons = directReading?.value
            val derivedGallons = derived?.value
            val difference = if (directGallons != null && derivedGallons != null)
                directGallons - derivedGallons else null
            val differencePct = if (difference != null && derivedGallons != null && derivedGallons > 0.0)
                difference / derivedGallons * 100.0 else null
            val directMpg = if (distance != null && directGallons != null && directGallons > 0.0)
                distance.value / directGallons else null
            DiagnosticLog.event("FUEL_CUMULATIVE_SAMPLE",
                "source=${state.optString("tripFuelSource", "ff1205_derived")} sessionMiles=${distance?.value} sessionSeconds=${time?.value} sessionGallons=${fuel?.value} dailyGallons=${daily().optDouble("gallons")} trip3Gallons=${trip3().optDouble("gallons")} tankGallons=${state.optDouble("gallons")}")
            if (metadataVerified) DiagnosticLog.event("FUEL_METHOD_COMPARE",
                "sessionMiles=${distance?.value} sessionMpg=${mpg?.value} directGallons=$directGallons derivedGallons=$derivedGallons deltaGallons=$difference deltaPct=$differencePct directMpg=$directMpg")
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
        val aplGeneration = PidSampleCache.generation()
        val rpm = read(ts, "0c,0")?.takeIf { it <= 7000.0 }
        if (rpm != null) {
            val rpmQuality = PidValidator.inspect(ts, "0c,0", rpm, 0.0, 7000.0, "fuel_rpm")
            PidSampleCache.publish(aplGeneration, AplPidInventory.RPM, rpm, rpmQuality.updateTime, rpmQuality.quality)
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
            .put("mpgMiles", state.optDouble("mpgMiles", state.optDouble("miles")))
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
        next.put("mpgMiles", 0.0)
        next.remove("pendingFuelMiles")
        next.remove("unmatchedFuelMiles")
        next.put("fuelCutoffEpochMs", id)
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
