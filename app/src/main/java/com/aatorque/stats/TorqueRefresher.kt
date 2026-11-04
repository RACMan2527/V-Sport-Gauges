package com.aatorque.stats

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Handler
import android.os.Looper
import com.aatorque.datastore.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import timber.log.Timber
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class PidCalibrationSummary(
    val measuredIntervalMs: Long,
    val recommendedIntervalMs: Long,
    val readSpeed: Float,
    val adapterErrors: Int,
    val sampleCount: Int,
    val activePidCount: Int,
    val qualifiedPidCount: Int,
    val excludedPidCount: Int,
    val ecuSampleCount: Int
)

enum class ConnectStatus {
    CONNECTING_TORQUE, CONNECTING_ECU, CONNECTED, SETUP_GAUGE
}

class TorqueRefresher {
    val data = HashMap<Int, TorqueData>()
    private val executor = ScheduledThreadPoolExecutor(7)
    val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val calibrationRequested = AtomicBoolean(false)
    val calibrationResults = MutableStateFlow<PidCalibrationSummary?>(null)
    @Volatile private var pollingEnabled = false
    @Volatile private var calibrationPolling = false
    @Volatile private var calibrationGeneration = 0
    val calibrationLiveMs = MutableStateFlow<Long?>(null)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        SAMPLE_INTERVAL = prefs.getLong(KEY_INTERVAL_MS, DEFAULT_SAMPLE_INTERVAL).coerceIn(50L, 1_000L)
        if (prefs.getBoolean(KEY_CALIBRATED, false) &&
            !prefs.getBoolean(KEY_RESULTS_DISMISSED, false)) {
            calibrationResults.value = PidCalibrationSummary(
                measuredIntervalMs = prefs.getLong(KEY_MEASURED_INTERVAL_MS, 0L),
                recommendedIntervalMs = SAMPLE_INTERVAL,
                readSpeed = prefs.getFloat(KEY_READ_SPEED, 0f),
                adapterErrors = prefs.getInt(KEY_ADAPTER_ERRORS, -1),
                sampleCount = prefs.getInt(KEY_SAMPLE_COUNT, 0),
                activePidCount = prefs.getInt(KEY_ACTIVE_PID_COUNT, 0),
                qualifiedPidCount = prefs.getInt(KEY_QUALIFIED_PID_COUNT, 0),
                excludedPidCount = prefs.getInt(KEY_EXCLUDED_PID_COUNT, 0),
                ecuSampleCount = prefs.getInt(KEY_ECU_SAMPLE_COUNT, 0)
            )
        }
    }
    private var conWatcher = MutableStateFlow(ConnectStatus.CONNECTING_TORQUE)
    val connectStatus = conWatcher.asSharedFlow()
    val cache = mutableMapOf<Int, HashMap<Int, TorqueData>>()
    companion object {
        const val DEFAULT_SAMPLE_INTERVAL = 200L
        const val PREFS_NAME = "pid_polling_settings"
        const val KEY_INTERVAL_MS = "sample_interval_ms"
        const val KEY_MEASURED_INTERVAL_MS = "measured_interval_ms"
        const val KEY_READ_SPEED = "read_speed"
        const val KEY_ADAPTER_ERRORS = "adapter_errors"
        const val KEY_SAMPLE_COUNT = "sample_count"
        const val KEY_ECU_SAMPLE_COUNT = "ecu_sample_count"
        const val KEY_CALIBRATED_AT = "calibrated_at"
        const val KEY_CALIBRATED = "calibrated"
        const val KEY_MANUAL_OVERRIDE = "manual_override"
        const val KEY_RESULTS_DISMISSED = "calibration_results_dismissed"
        const val KEY_ACTIVE_PID_COUNT = "active_pid_count"
        const val KEY_QUALIFIED_PID_COUNT = "qualified_pid_count"
        const val KEY_EXCLUDED_PID_COUNT = "excluded_pid_count"

        @Volatile
        var SAMPLE_INTERVAL = DEFAULT_SAMPLE_INTERVAL

        @Volatile
        var REFRESH_INTERVAL = 300L
    }

    fun onDashboardChanged() {
        calibrationGeneration++
        calibrationLiveMs.value = null
    }

    fun populateQuery(pos: Int, screen: Int, query: Display): TorqueData {
        data[pos]?.stopRefreshing(true)
        val cacheItem = cache[screen]?.get(pos)
        val torqueData = if (cacheItem?.display?.equals(query) == true) {
            cacheItem
        } else {
            TorqueData(query)
        }
        cache.getOrPut(screen) {
            HashMap()
        }[pos] = torqueData
        data[pos] = torqueData
        Timber.i("Setting query: $query for pos $pos on screen $screen")
        return torqueData
    }

    suspend fun makeExecutors(service: TorqueService) {
        pollingEnabled = true
        var foundValid = false

        // SAMPLE_INTERVAL is the persisted/calibrated PID polling period.
        // REFRESH_INTERVAL is the separate user-controlled gauge animation duration.
        data.values.forEachIndexed { index, torqueData ->
            val period = if (calibrationPolling && PidPollingCalibration.isQualifiedEcuPid(torqueData.pid ?: "")) 100L else SAMPLE_INTERVAL
            val refreshOffset = (period / data.size) * index
            if (torqueData.pid != null) {
                foundValid = true
                if (torqueData.refreshTimer == null) {
                    Timber.i("Scheduled item in position $index with $refreshOffset delay")
                    doRefresh(service, torqueData)
                    torqueData.refreshTimer = executor.scheduleWithFixedDelay({
                        try {
                            doRefresh(service, torqueData)
                        } catch (e: Exception) {
                            Timber.e("Refresh failed in pos $index", e)
                        }
                    }, refreshOffset, period, TimeUnit.MILLISECONDS)
                }
            } else {
                Timber.i("No reason to schedule item in position $index")
            }
        }
        conWatcher.emit(if (foundValid) conWatcher.value else ConnectStatus.SETUP_GAUGE)
        service.addConnectCallback {
            if (conWatcher.value == ConnectStatus.CONNECTING_TORQUE) {
                runBlocking {
                    conWatcher.emit(ConnectStatus.CONNECTING_ECU)
                }
            }
        }
        startCalibrationIfNeeded(service)
    }

    private fun startCalibrationIfNeeded(service: TorqueService) {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CALIBRATED, false) ||
            prefs.getBoolean(KEY_MANUAL_OVERRIDE, false) ||
            !calibrationRequested.compareAndSet(false, true)) return
        val activePids = data.values.mapNotNull { it.pid }.distinct()
        if (activePids.isEmpty()) {
            calibrationRequested.set(false)
            return
        }
        val generation = calibrationGeneration
        service.addConnectCallback { torque ->
            Thread({
                fun active(): Boolean = pollingEnabled && generation == calibrationGeneration &&
                    data.values.mapNotNull { it.pid }.distinct().sorted() == activePids.sorted()
                try {
                    // Binding to Torque is not equivalent to a live ECU connection.
                    // Wait for ECU and an advancing timestamp before the five-second delay.
                    val qualified = activePids.filter(PidPollingCalibration::isQualifiedEcuPid)
                    var lastTimestamp = 0L
                    while (active()) {
                        if (runCatching { torque.isConnectedToECU }.getOrDefault(false) && qualified.isNotEmpty()) {
                            val times = runCatching { torque.getPIDUpdateTime(qualified.toTypedArray()) }.getOrNull()
                            val latest = times?.maxOrNull() ?: 0L
                            if (latest > 0L && lastTimestamp > 0L && latest > lastTimestamp) break
                            lastTimestamp = latest
                        } else lastTimestamp = 0L
                        Thread.sleep(200L)
                    }
                    if (!active()) return@Thread
                    val readyAt = System.currentTimeMillis()
                    while (active() && System.currentTimeMillis() - readyAt < 5_000L) {
                        if (!runCatching { torque.isConnectedToECU }.getOrDefault(false)) return@Thread
                        Thread.sleep(100L)
                    }
                    if (!active()) return@Thread
                    calibrationPolling = true
                    data.values.forEach { it.stopRefreshing() }
                    runBlocking { makeExecutors(service) }
                    calibrationLiveMs.value = 100L
                    val result = PidPollingCalibration.run(torque, activePids,
                        { active() },
                        { interval -> if (active()) calibrationLiveMs.value = interval })
                    if (active() && result != null) {
                        PidPollingCalibration.save(context, result)
                        SAMPLE_INTERVAL = result.recommendedIntervalMs
                        calibrationLiveMs.value = null
                        if (!context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .getBoolean(KEY_RESULTS_DISMISSED, false)) {
                            calibrationResults.value = PidCalibrationSummary(
                                result.measuredIntervalMs, result.recommendedIntervalMs,
                                result.readSpeed.toFloat(), result.adapterErrors,
                                result.sampleCount, result.activePidCount,
                                result.qualifiedPidCount, result.excludedPidCount,
                                result.ecuSampleCount)
                        }
                        Timber.i("PID calibration complete: ${result.recommendedIntervalMs}ms; qualified=${result.qualifiedPidCount}; excluded=${result.excludedPidCount}")
                    } else if (active()) {
                        Timber.w("PID calibration failed: no qualifying fresh ECU timestamps")
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    Timber.e(e, "PID calibration failed")
                } finally {
                    calibrationLiveMs.value = null
                    calibrationPolling = false
                    if (pollingEnabled) {
                        data.values.forEach { it.stopRefreshing() }
                        runBlocking { makeExecutors(service) }
                    }
                    calibrationRequested.set(false)
                    if (pollingEnabled && generation != calibrationGeneration) {
                        startCalibrationIfNeeded(service)
                    }
                }
            }, "pid-polling-calibration").start()
        }
    }

    fun doRefresh(service: TorqueService, torqueData: TorqueData) {
        service.runIfConnected { ts ->
            val value = try {
                ts.getPIDValuesAsDouble(arrayOf(torqueData.pid!!))[0]
            } catch (e: ArrayIndexOutOfBoundsException) {
                Timber.e("Torque returned invalid data for ${torqueData.pid}")
                return@runIfConnected
            }
            if (!torqueData.updateReading(value)) {
                return@runIfConnected
            }
            torqueData.hasReceivedReading = true
            Timber.d("Got valid $value from torque for ${torqueData.display.label}")
            if (value != 0.0) {
                torqueData.hasReceivedNonZero = true
            }
            if (value != 0.0 || torqueData.hasReceivedNonZero || torqueData.allowInitialZeroUpdates) {
                handler.post {
                    torqueData.sendNotifyUpdate()
                }
            }
            // Preserve master's connection rule: CONNECTED only after a non-zero
            // reading has arrived, or after a previously non-zero reading.
            if (value != 0.0 || torqueData.hasReceivedNonZero) {
                runBlocking {
                    conWatcher.emit(ConnectStatus.CONNECTED)
                }
            }
        }
    }

    fun stopExecutors() {
        pollingEnabled = false
        calibrationGeneration++
        calibrationLiveMs.value = null
        Timber.i("Telling Torque refreshers to stop")
        for (td in data.values) {
            td.stopRefreshing()
        }
    }

    fun hasChanged(idx: Int, otherScreen: Display?): Boolean {
        if (!data.containsKey(idx)) return true
        return data[idx]?.display?.equals(otherScreen) != true
    }

    fun updateIfNeeded(pos: Int, screen: Int, query: Display): TorqueData {
        return if (hasChanged(pos, query)) {
            populateQuery(pos, screen, query)
        } else {
            data[pos]!!
        }
    }

}
