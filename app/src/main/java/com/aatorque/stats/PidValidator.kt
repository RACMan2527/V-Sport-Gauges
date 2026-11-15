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
    fun resetSource() { previousUpdates.clear() }
}
