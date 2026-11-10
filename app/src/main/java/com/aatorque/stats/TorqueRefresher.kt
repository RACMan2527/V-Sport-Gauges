package com.aatorque.stats

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aatorque.datastore.Display
import com.aatorque.datastore.MaxControl
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
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
    private val rpmValidationExecutor = ScheduledThreadPoolExecutor(1)
    private data class PendingMinimum(var lowest: Double, val date: String, var task: ScheduledFuture<*>? = null)
    private val pendingMinimums = ConcurrentHashMap<TorqueData, PendingMinimum>()
    val handler = Handler(Looper.getMainLooper())
    @Volatile private var pollingEnabled = false
    private var activeService: TorqueService? = null
    private lateinit var appContext: Context
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

        private val instances = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<TorqueRefresher, Boolean>()
        )

        fun setGlobalPollingInterval(context: Context, interval: Long) {
            val value = interval.coerceIn(50L, 500L)
            SAMPLE_INTERVAL = value
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_INTERVAL_MS, value)
                .putBoolean("manual_polling_initialized", true).apply()
            val targets = synchronized(instances) { instances.toList() }
            targets.forEach { it.reschedulePolling() }
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        synchronized(instances) { instances.add(this) }
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

        // SAMPLE_INTERVAL is the persisted manual PID polling period.
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
            if (data[7] === torqueData) {
                DailyPeakStore.recordMaximum(appContext, torqueData.display, torqueData.lastData, horizontalBar = true)
            } else when (torqueData.display.maxMarksActive) {
                MaxControl.MAX -> DailyPeakStore.recordMaximum(appContext, torqueData.display, torqueData.lastData)
                MaxControl.MIN -> validateMinimumIfNeeded(service, torqueData, torqueData.lastData)
                else -> Unit
            }
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

    /**
     * Validate each new daily minimum over one fixed five-second window.
     * Subsequent lower readings update the candidate without resetting its timer.
     * RPM is checked only at the beginning and end of the window.
     */
    private fun validateMinimumIfNeeded(service: TorqueService, gauge: TorqueData, candidate: Double) {
        if (!pollingEnabled || !DailyPeakStore.isNewMinimum(appContext, gauge.display, candidate)) return
        synchronized(pendingMinimums) {
            val existing = pendingMinimums[gauge]
            if (existing != null) {
                if (candidate < existing.lowest) existing.lowest = candidate
                return
            }
            val date = LocalDate.now().toString()
            try {
                var rpmValid = false
                service.runIfConnected { ts ->
                    val rpm = ts.getPIDValuesAsDouble(arrayOf("0C")).firstOrNull()
                    rpmValid = rpm != null && rpm.isFinite() && rpm > 300.0
                }
                if (!rpmValid) return
                val pending = PendingMinimum(candidate, date)
                pendingMinimums[gauge] = pending
                pending.task = rpmValidationExecutor.schedule({
                    try {
                        if (pollingEnabled && activeService === service &&
                            gauge in this.data.values && date == LocalDate.now().toString()) {
                            service.runIfConnected { ts ->
                                val rpm = ts.getPIDValuesAsDouble(arrayOf("0C")).firstOrNull()
                                if (rpm != null && rpm.isFinite() && rpm > 300.0) {
                                    val minimum = synchronized(pendingMinimums) { pending.lowest }
                                    DailyPeakStore.recordMinimum(appContext, gauge.display, minimum, date)
                                    handler.post { if (pollingEnabled) gauge.sendNotifyUpdate() }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "RPM minimum validation failed; candidate rejected")
                    } finally {
                        synchronized(pendingMinimums) {
                            if (pendingMinimums[gauge] === pending) pendingMinimums.remove(gauge)
                        }
                    }
                }, 5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                pendingMinimums.remove(gauge)
                Timber.w(e, "Initial RPM minimum validation failed; candidate rejected")
            }
        }
    }

    private fun reschedulePolling() {
        handler.post {
            val service = activeService ?: return@post
            if (!pollingEnabled) return@post
            data.values.forEach { it.stopRefreshing() }
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                if (pollingEnabled) makeExecutors(service)
            }
        }
    }

    fun stopExecutors() {
        pollingEnabled = false
        pendingMinimums.values.forEach { it.task?.cancel(false) }
        pendingMinimums.clear()
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
