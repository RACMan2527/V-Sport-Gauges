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

    fun inspect(ts: ITorqueService, pid: String, value: Double?,
                minimum: Double = Double.NEGATIVE_INFINITY,
                maximum: Double = Double.POSITIVE_INFINITY,
                consumer: String = "gauges"): Result {
        val result = try {
            if (!ts.isConnectedToECU || value == null || !value.isFinite() ||
                value < minimum || value > maximum) {
                Result(Quality.SUSPECT, 0L)
            } else {
                val timestamp = ts.getPIDUpdateTime(arrayOf(pid)).firstOrNull() ?: 0L
                if (timestamp <= 0L) Result(Quality.SUSPECT, timestamp)
                else {
                    val previous = previousUpdates.put("$consumer:$pid", timestamp)
                    if (previous == timestamp) Result(Quality.STALE, timestamp)
                    else Result(Quality.VALID, timestamp)
                }
            }
        } catch (_: Exception) {
            Result(Quality.SUSPECT, 0L)
        }
        when (result.quality) {
            Quality.VALID -> validCount.incrementAndGet()
            Quality.STALE -> staleCount.incrementAndGet()
            Quality.SUSPECT -> suspectCount.incrementAndGet()
        }
        return result
    }

    fun snapshot() = Snapshot(validCount.get(), staleCount.get(), suspectCount.get())

    // Reset source-update history when the ECU connection is lost or recreated.
    fun resetSource() { previousUpdates.clear() }
}
