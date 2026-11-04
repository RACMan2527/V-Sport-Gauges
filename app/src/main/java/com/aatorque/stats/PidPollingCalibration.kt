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
        val activePidCount: Int,
        val ecuSampleCount: Int,
        val qualifiedPidCount: Int,
        val excludedPidCount: Int
    )

    fun run(service: ITorqueService, pids: List<String>, isActive: () -> Boolean, onInterval: (Long) -> Unit): Result? {
        if (pids.isEmpty()) return null

        val intervals = pids.associateWith { mutableListOf<Long>() }
        val previous = HashMap<String, Long>()
        val startErrors = runCatching { service.dataErrorCount }.getOrDefault(-1)
        val startedAt = System.currentTimeMillis()
        val durationMs = 15_000L

        while (System.currentTimeMillis() - startedAt < durationMs) {
            if (!isActive() || !runCatching { service.isConnectedToECU }.getOrDefault(false)) return null
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
                    if (isQualifiedEcuPid(pid)) onInterval(current - prior)
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

        // Qualify diagnostic request identifiers dynamically for this dashboard.
        // Virtual/calculated identifiers are measured but excluded from the recommendation.
        val qualifying = intervals.filter { (pid, samples) ->
            isQualifiedEcuPid(pid) && samples.size >= 4
        }
        if (qualifying.isEmpty()) {
            Timber.w("Calibration: no verified ECU PID supplied four fresh intervals. Samples: ${intervals.mapValues { it.value.size }}")
            return null
        }
        val perPidP75 = qualifying.values.map { samples ->
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
            activePidCount = pids.size,
            ecuSampleCount = qualifying.values.sumOf { it.size },
            qualifiedPidCount = qualifying.size,
            excludedPidCount = pids.size - qualifying.size
        )
    }

    /**
     * Torque's identifier begins with the actual diagnostic request (see
     * ITorqueService.listAllPIDs). Standard OBD modes 01/02/09 and GM
     * enhanced modes 21/22/23 represent ECU requests, not calculated
     * Torque trip/virtual sensors. Do not restrict this to named PIDs.
     *
     * An optional Torque display suffix is separated from the command
     * with a non-hex character. No ECU support is assumed from this
     * classification alone: fresh timestamps are still required.
     */
    fun isQualifiedEcuPid(pid: String): Boolean {
        val identifier = pid.removePrefix("torque_").uppercase()
        val command = identifier.takeWhile { it in '0'..'9' || it in 'A'..'F' }
        if (command.length != 4 && command.length != 6) return false
        return when (command.take(2)) {
            "01", "02", "09", "21", "22", "23" -> true
            else -> false
        }
    }

    fun save(context: Context, result: Result) {
        context.getSharedPreferences(TorqueRefresher.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(TorqueRefresher.KEY_INTERVAL_MS, result.recommendedIntervalMs)
            .putLong(TorqueRefresher.KEY_MEASURED_INTERVAL_MS, result.measuredIntervalMs)
            .putFloat(TorqueRefresher.KEY_READ_SPEED, result.readSpeed.toFloat())
            .putInt(TorqueRefresher.KEY_ADAPTER_ERRORS, result.adapterErrors)
            .putInt(TorqueRefresher.KEY_SAMPLE_COUNT, result.sampleCount)
            .putInt(TorqueRefresher.KEY_ECU_SAMPLE_COUNT, result.ecuSampleCount)
            .putInt(TorqueRefresher.KEY_ACTIVE_PID_COUNT, result.activePidCount)
            .putInt(TorqueRefresher.KEY_QUALIFIED_PID_COUNT, result.qualifiedPidCount)
            .putInt(TorqueRefresher.KEY_EXCLUDED_PID_COUNT, result.excludedPidCount)
            .putLong(TorqueRefresher.KEY_CALIBRATED_AT, System.currentTimeMillis())
            .putBoolean(TorqueRefresher.KEY_CALIBRATED, true)
            .apply()
    }
}
