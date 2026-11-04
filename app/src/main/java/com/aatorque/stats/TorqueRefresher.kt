package com.aatorque.stats

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aatorque.datastore.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private var activeService: TorqueService? = null
    private var conWatcher = MutableStateFlow(ConnectStatus.CONNECTING_TORQUE)
    val connectStatus = conWatcher.asSharedFlow()
    val cache = mutableMapOf<Int, HashMap<Int, TorqueData>>()
    companion object {
        const val DEFAULT_SAMPLE_INTERVAL = 200L
        const val PREFS_NAME = "pid_polling_settings"
        const val KEY_INTERVAL_MS = "sample_interval_ms"
        @Volatile
        var SAMPLE_INTERVAL = DEFAULT_SAMPLE_INTERVAL

        @Volatile
        var REFRESH_INTERVAL = 300L
    }

    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Ignore any interval produced by the removed automatic calibration.
        if (!prefs.getBoolean("manual_polling_initialized", false)) {
            prefs.edit().putLong(KEY_INTERVAL_MS, DEFAULT_SAMPLE_INTERVAL)
                .putBoolean("manual_polling_initialized", true).apply()
        }
        SAMPLE_INTERVAL = prefs.getLong(KEY_INTERVAL_MS, DEFAULT_SAMPLE_INTERVAL).coerceIn(50L, 500L)
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
        activeService = service
        var foundValid = false

        // SAMPLE_INTERVAL is the persisted/calibrated PID polling period.
        // REFRESH_INTERVAL is the separate user-controlled gauge animation duration.
        data.values.forEachIndexed { index, torqueData ->
            val period = SAMPLE_INTERVAL
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

    /** Apply a manual polling interval without losing existing TorqueData or gauge peaks. */
    @Synchronized
    fun setPollingInterval(context: Context, interval: Long) {
        val value = interval.coerceIn(50L, 500L)
        SAMPLE_INTERVAL = value
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_INTERVAL_MS, value).apply()
        // Existing scheduled tasks retain their original period until rescheduled.
        if (pollingEnabled) {
            data.values.forEach { it.stopRefreshing() }
            activeService?.let { service ->
                handler.post { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    makeExecutors(service)
                } }
            }
        }
    }

    fun stopExecutors() {
        pollingEnabled = false
        activeService = null
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
