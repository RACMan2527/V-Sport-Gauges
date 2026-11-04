package com.aatorque.stats

import android.content.Context
import org.prowl.torque.remote.ITorqueService
import timber.log.Timber
import kotlin.math.ceil

/**
 * Measures fresh ECU update timestamps for the PIDs on the active dashboard.
 * The recommendation uses the slowest per-PID 75th-percentile update interval,
 * then adds 10% headroom so the app does not continually request cached values.
 */
internal object PidPollingCalibration {
    data class Result(
        val recommendedIntervalMs: Long,
        val measuredIntervalMs: Long,
        val readSpeed: Double,
        val adapterErrors: Int,
        val sampleCount: Int,
        val activePidCount: Int
    )

    fun run(service: ITorqueService, pids: List<String>): Result? {
        if (pids.isEmpty()) return null

        val intervals = pids.associateWith { mutableListOf<Long>() }
        val previous = HashMap<String, Long>()
        val startErrors = runCatching { service.dataErrorCount }.getOrDefault(-1)
        val startedAt = System.currentTimeMillis()
        val durationMs = 8_000L

        while (System.currentTimeMillis() - startedAt < durationMs) {
            val timestamps = try {
                service.getPIDUpdateTime(pids.toTypedArray())
            } catch (e: Exception) {
                Timber.e(e, "PID polling calibration timestamp request failed")
                return null
            }

            if (timestamps.size != pids.size) {
                Timber.w("PID polling calibration returned ${timestamps.size} timestamps for ${pids.size} PIDs")
                return null
            }

            pids.forEachIndexed { index, pid ->
                val current = timestamps[index]
                val prior = previous[pid]
                if (current > 0L && prior != null && current > prior) {
                    intervals[pid]?.add(current - prior)
                }
                if (current > 0L) previous[pid] = current
            }

            try {
                Thread.sleep(25L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }

        // Require at least four fresh intervals for every active PID. A PID that
        // did not update enough during calibration must not be silently ignored.
        if (intervals.values.any { it.size < 4 }) {
            Timber.w("PID polling calibration did not receive enough fresh updates for every active PID")
            return null
        }

        val perPidP75 = intervals.values.map { samples ->
            val sorted = samples.sorted()
            val index = ceil((sorted.size - 1) * 0.75).toInt().coerceIn(0, sorted.lastIndex)
            sorted[index]
        }
        val measured = perPidP75.maxOrNull() ?: return null
        if (measured <= 0L) return null

        val recommended = ceil(measured * 1.10).toLong().coerceIn(50L, 1_000L)
        val readSpeed = runCatching { service.pidReadSpeed }.getOrDefault(0.0)
        val endErrors = runCatching { service.dataErrorCount }.getOrDefault(startErrors)
        val errors = if (startErrors >= 0 && endErrors >= startErrors) endErrors - startErrors else -1

        return Result(
            recommendedIntervalMs = recommended,
            measuredIntervalMs = measured,
            readSpeed = readSpeed,
            adapterErrors = errors,
            sampleCount = intervals.values.sumOf { it.size },
            activePidCount = pids.size
        )
    }

    fun save(context: Context, result: Result) {
        context.getSharedPreferences(TorqueRefresher.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(TorqueRefresher.KEY_INTERVAL_MS, result.recommendedIntervalMs)
            .putLong(TorqueRefresher.KEY_MEASURED_INTERVAL_MS, result.measuredIntervalMs)
            .putFloat(TorqueRefresher.KEY_READ_SPEED, result.readSpeed.toFloat())
            .putInt(TorqueRefresher.KEY_ADAPTER_ERRORS, result.adapterErrors)
            .putInt(TorqueRefresher.KEY_SAMPLE_COUNT, result.sampleCount)
            .putInt(TorqueRefresher.KEY_ACTIVE_PID_COUNT, result.activePidCount)
            .putLong(TorqueRefresher.KEY_CALIBRATED_AT, System.currentTimeMillis())
            .putBoolean(TorqueRefresher.KEY_CALIBRATED, true)
            .apply()
    }
}
