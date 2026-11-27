package com.aatorque.stats

import org.prowl.torque.remote.ITorqueService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Classifies Torque's cached PID values without issuing any extra ECU requests.
 * An API return is not proof of a new ECU sample: getPIDUpdateTime is inspected.
 *
 * Timestamp units are deliberately not assumed. Only positive, changed tokens
 * count as new source updates; repeated numeric values are allowed.
 */
object PidValidator {
    enum class Quality { VALID, STALE, SUSPECT }
    data class Result(val quality: Quality, val updateTime: Long)
    data class Snapshot(val valid: Long, val stale: Long, val suspect: Long) {
        val total: Long get() = valid + stale + suspect
    }

    // Independent, passive freshness diagnostic. Existing quality and suspect accounting stay intact.
    private data class SourceAge(val token: Long, val observedAtMs: Long)
    private data class AgeObservation(val atMs: Long, val expired: Boolean)
    private val sourceAges = ConcurrentHashMap<String, SourceAge>()
    private val recentAges = ArrayDeque<AgeObservation>()
    private const val WINDOW_MS = 60_000L
    private fun freshnessLimitMs(pid: String): Long {
        val id = pid.lowercase(java.util.Locale.US).replace(" ", "")
        // Standard OBD temperature modes; GM extended temperatures require field calibration.
        return when {
            // Torque supplies standard PIDs as "05,0" and extended GM PIDs
            // as "221940,1981320"; the header suffix is not part of the PID.
            // Confirmed temperature PIDs: coolant (05), intake (0f),
            // transmission (221940), and engine oil (221154).
            id.substringBefore(',') in setOf("05", "0f", "46", "5c",
                "0105", "010f", "0146", "015c", "221940", "221154") -> 15_000L
            id.contains("trip") -> 10_000L
            else -> 2_000L
        }
    }

    private fun recordFreshness(pid: String, timestamp: Long, eligible: Boolean) {
        if (!eligible || timestamp <= 0L) return
        val now = android.os.SystemClock.elapsedRealtime()
        // An initial cached token is not proof of a fresh ECU sample.
        val old = sourceAges[pid]
        val updated = if (old == null || old.token != timestamp) {
            SourceAge(timestamp, now)
        } else old
        sourceAges[pid] = updated
        val expired = old != null && old.token == timestamp &&
            now - updated.observedAtMs > freshnessLimitMs(pid)
        synchronized(recentAges) {
            recentAges.addLast(AgeObservation(now, expired))
            while (recentAges.isNotEmpty() && now - recentAges.first().atMs > WINDOW_MS) {
                recentAges.removeFirst()
            }
        }
    }

    /** Guard uses actual source age where available, without changing dashboard diagnostics. */
    fun sourceExpired(pid: String, token: Long, now: Long = android.os.SystemClock.elapsedRealtime(),
                      wall: Long = System.currentTimeMillis()): Boolean {
        if (token <= 0L) return false
        if (token >= 1_000_000_000_000L) return token > wall || wall - token > freshnessLimitMs(pid)
        val observed = sourceAges[pid] ?: return false
        return observed.token == token && now - observed.observedAtMs > freshnessLimitMs(pid)
    }

    data class ExpiredSnapshot(val expired: Int, val total: Int)
    fun expiredSnapshot(): ExpiredSnapshot {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(recentAges) {
            while (recentAges.isNotEmpty() && now - recentAges.first().atMs > WINDOW_MS) {
                recentAges.removeFirst()
            }
            return ExpiredSnapshot(recentAges.count { it.expired }, recentAges.size)
        }
    }

    /** Bounds are supplied by Torque Pro, not by a gauge's display range. */
    data class Bounds(val minimum: Double, val maximum: Double, val unit: String)
    private val metadata = ConcurrentHashMap<String, Bounds>()
    private val metadataLoadLock = Any()
    @Volatile private var metadataLoading = false
    @Volatile private var metadataLoaded = false
    private const val BOUND_TOLERANCE = 1.0

    fun bounds(pid: String): Bounds? = metadata[pid]

    /** Metadata is fetched once per Torque connection, off the polling thread. */
    fun loadMetadataAsync(ts: ITorqueService) {
        synchronized(metadataLoadLock) {
            if (metadataLoading || metadataLoaded) return
            metadataLoading = true
        }
        Thread({
            try {
                val ids = ts.listAllPIDs()
                val info = ts.getPIDInformation(ids)
                val loaded = HashMap<String, Bounds>()
                ids.zip(info).forEach { (id, csv) ->
                    val fields = csv.split(",")
                    if (fields.size >= 5) {
                        val max = fields[3].trim().toDoubleOrNull()
                        val min = fields[4].trim().toDoubleOrNull()
                        if (min != null && max != null && min.isFinite() &&
                            max.isFinite() && min < max) {
                            loaded[id] = Bounds(min, max, fields[2].trim())
                        }
                    }
                }
                metadata.clear()
                metadata.putAll(loaded)
                metadataLoaded = true
                DiagnosticLog.event("PID_BOUNDS_LOADED",
                    "available=${loaded.size} total=${ids.size} tolerance=$BOUND_TOLERANCE")
            } catch (e: Exception) {
                DiagnosticLog.event("PID_BOUNDS_LOAD_FAILED", "reason=${e.javaClass.simpleName}")
            } finally {
                synchronized(metadataLoadLock) { metadataLoading = false }
            }
        }, "V-Sport-PID-Metadata").apply { isDaemon = true }.start()
    }

    fun resetMetadata() {
        metadata.clear()
        metadataLoaded = false
    }

    private val previousUpdates = ConcurrentHashMap<String, Long>()
    private val validCount = AtomicLong()
    private val staleCount = AtomicLong()
    private val suspectCount = AtomicLong()
    private val suspectReasons = ConcurrentHashMap<String, AtomicLong>()
    private fun recordSuspect(pid: String, reason: String, value: Double?, bounds: Bounds?, timestamp: Long, consumer: String) {
        val count = suspectReasons.computeIfAbsent("$pid|$reason") { AtomicLong() }.incrementAndGet()
        // Bounded diagnostics: first event and each 100th occurrence per PID/reason.
        DiagnosticLog.repeatedSuspect(pid, reason,
            "pid=$pid consumer=$consumer raw=$value unit=${bounds?.unit ?: "unknown"} " +
            "min=${bounds?.minimum ?: "unknown"} max=${bounds?.maximum ?: "unknown"} " +
            "tolerance=$BOUND_TOLERANCE sourceTimestamp=$timestamp count=$count")

    }

    fun inspect(ts: ITorqueService, pid: String, value: Double?,
                minimum: Double = Double.NEGATIVE_INFINITY,
                maximum: Double = Double.POSITIVE_INFINITY,
                consumer: String = "gauges"): Result {
        // A disconnected ECU is a connection state, not a data-quality sample.
        // Preserve SUSPECT for callers that reject unusable values, but do not count it.
        var disconnected = false
        var suspectReason = "unknown"
        val pidBounds = metadata[pid]
        val lower = pidBounds?.minimum?.minus(BOUND_TOLERANCE) ?: minimum
        val upper = pidBounds?.maximum?.plus(BOUND_TOLERANCE) ?: maximum
        val result = try {
            disconnected = !ts.isConnectedToECU
            if (disconnected || value == null || !value.isFinite() ||
                value < lower || value > upper) {
                suspectReason = when {
                    disconnected -> "ecu_disconnected"
                    value == null -> "null_value"
                    !value.isFinite() -> "nonfinite_value"
                    else -> "out_of_range"
                }
                Result(Quality.SUSPECT, 0L)
            } else {
                val timestamp = ts.getPIDUpdateTime(arrayOf(pid)).firstOrNull() ?: 0L
                if (timestamp <= 0L) {
                    suspectReason = "missing_timestamp"
                    Result(Quality.SUSPECT, timestamp)
                }
                else {
                    val previous = previousUpdates.put("$consumer:$pid", timestamp)
                    if (previous == timestamp) Result(Quality.STALE, timestamp)
                    else Result(Quality.VALID, timestamp)
                }
            }
        } catch (e: Exception) {
            suspectReason = "exception_${e.javaClass.simpleName}"
            Result(Quality.SUSPECT, 0L)
        }
        // Do not alter Quality, counters, or the existing Suspect Data denominator.
        recordFreshness(pid, result.updateTime, !disconnected && result.quality != Quality.SUSPECT)
        when (result.quality) {
            Quality.VALID -> validCount.incrementAndGet()
            Quality.STALE -> staleCount.incrementAndGet()
            Quality.SUSPECT -> if (!disconnected) {
                suspectCount.incrementAndGet()
                recordSuspect(pid, suspectReason, value, pidBounds, result.updateTime, consumer)
            }
        }
        return result
    }

    fun snapshot() = Snapshot(validCount.get(), staleCount.get(), suspectCount.get())

    // Reset source-update history when the ECU connection is lost or recreated.
    fun resetSource() { previousUpdates.clear(); sourceAges.clear(); synchronized(recentAges) { recentAges.clear() } }
}
