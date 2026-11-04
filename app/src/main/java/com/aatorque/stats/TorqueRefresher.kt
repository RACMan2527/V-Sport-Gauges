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

enum class ConnectStatus {
    CONNECTING_TORQUE, CONNECTING_ECU, CONNECTED, SETUP_GAUGE
}

class TorqueRefresher {
    val data = HashMap<Int, TorqueData>()
    private val executor = ScheduledThreadPoolExecutor(7)
    val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val calibrationRequested = AtomicBoolean(false)
    @Volatile private var pollingEnabled = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
        SAMPLE_INTERVAL = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_INTERVAL_MS, DEFAULT_SAMPLE_INTERVAL)
            .coerceIn(50L, 1_000L)
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
        const val KEY_CALIBRATED_AT = "calibrated_at"
        const val KEY_CALIBRATED = "calibrated"
        const val KEY_MANUAL_OVERRIDE = "manual_override"

        @Volatile
        var SAMPLE_INTERVAL = DEFAULT_SAMPLE_INTERVAL

        @Volatile
        var REFRESH_INTERVAL = 300L
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
            val refreshOffset = (SAMPLE_INTERVAL / data.size) * index
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
                    }, refreshOffset, SAMPLE_INTERVAL, TimeUnit.MILLISECONDS)
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

        service.addConnectCallback { torque ->
            Thread({
                var dashboardChangedDuringCalibration = false
                try {
                    Timber.i("Starting first-run PID polling calibration for ${activePids.size} active PIDs")
                    val result = PidPollingCalibration.run(torque, activePids)
                    val currentPids = data.values.mapNotNull { it.pid }.distinct()
                    if (currentPids.sorted() != activePids.sorted()) {
                        dashboardChangedDuringCalibration = true
                        Timber.i("Discarding PID polling calibration because the active dashboard changed during measurement")
                    } else if (result == null) {
                        Timber.w("PID polling calibration did not produce enough fresh ECU samples; it will retry on a later dashboard start")
                    } else {
                        PidPollingCalibration.save(context, result)
                        SAMPLE_INTERVAL = result.recommendedIntervalMs
                        Timber.i("PID polling calibration: measured=${result.measuredIntervalMs}ms, recommended=${result.recommendedIntervalMs}ms, readSpeed=${result.readSpeed}, adapterErrors=${result.adapterErrors}")

                        if (pollingEnabled) {
                            data.values.forEach { it.stopRefreshing() }
                            runBlocking { makeExecutors(service) }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "PID polling calibration failed")
                } finally {
                    calibrationRequested.set(false)
                    if (dashboardChangedDuringCalibration && pollingEnabled) {
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
