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
            id.startsWith("0105") || id.startsWith("010f") ||
                id.startsWith("0146") || id.startsWith("015c") -> 15_000L
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

    private val previousUpdates = ConcurrentHashMap<String, Long>()
    private val validCount = AtomicLong()
    private val staleCount = AtomicLong()
    private val suspectCount = AtomicLong()
    private val suspectReasons = ConcurrentHashMap<String, AtomicLong>()
    private fun recordSuspect(pid: String, reason: String) {
        val count = suspectReasons.computeIfAbsent("$pid|$reason") { AtomicLong() }.incrementAndGet()
        // Bounded diagnostics: first event and each 100th occurrence per PID/reason.
        if (count == 1L || count % 100L == 0L) {
            DiagnosticLog.event("PID_SUSPECT_SUMMARY", "pid=$pid reason=$reason count=$count")
        }
    }

    fun inspect(ts: ITorqueService, pid: String, value: Double?,
                minimum: Double = Double.NEGATIVE_INFINITY,
                maximum: Double = Double.POSITIVE_INFINITY,
                consumer: String = "gauges"): Result {
        // A disconnected ECU is a connection state, not a data-quality sample.
        // Preserve SUSPECT for callers that reject unusable values, but do not count it.
        var disconnected = false
        var suspectReason = "unknown"
        val result = try {
            disconnected = !ts.isConnectedToECU
            if (disconnected || value == null || !value.isFinite() ||
                value < minimum || value > maximum) {
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
                recordSuspect(pid, suspectReason)
            }
        }
        return result
    }

    fun snapshot() = Snapshot(validCount.get(), staleCount.get(), suspectCount.get())

    // Reset source-update history when the ECU connection is lost or recreated.
    fun resetSource() { previousUpdates.clear(); sourceAges.clear(); synchronized(recentAges) { recentAges.clear() } }
}
