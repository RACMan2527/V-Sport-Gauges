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
import kotlinx.coroutines.flow.asStateFlow
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
    private data class PendingMinimum(val date: String, var lowest: Double? = null, var collecting: Boolean = false, var task: ScheduledFuture<*>? = null)
    private var activeScreen: Int? = null
    private val pendingMinimums = ConcurrentHashMap<TorqueData, PendingMinimum>()
    val handler = Handler(Looper.getMainLooper())
    @Volatile private var pollingEnabled = false
    private var activeService: TorqueService? = null
    private lateinit var appContext: Context
    private var conWatcher = MutableStateFlow(ConnectStatus.CONNECTING_TORQUE)
    val connectStatus = conWatcher.asSharedFlow()
    private val failedPolls = ConcurrentHashMap<TorqueData, Int>()
    private val commFaultState = MutableStateFlow(false)
    val communicationFault = commFaultState.asStateFlow()

    // Passive diagnostics: no extra PID requests and no changes to scheduling.
    data class DiagnosticsSnapshot(
        val successfulPolls: Long,
        val failedPolls: Long,
        val lastResponseMs: Long,
        val lastSuccessAtMs: Long
    )
    private val diagSuccess = java.util.concurrent.atomic.AtomicLong()
    private val diagFailures = java.util.concurrent.atomic.AtomicLong()
    private val diagLastResponseMs = java.util.concurrent.atomic.AtomicLong(-1L)
    private val diagLastSuccessAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    fun diagnosticsSnapshot(): DiagnosticsSnapshot = DiagnosticsSnapshot(
        diagSuccess.get(), diagFailures.get(),
        diagLastResponseMs.get(), diagLastSuccessAtMs.get()
    )

    private fun markPollResult(gauge: TorqueData, successful: Boolean) {
        if (!pollingEnabled) return
        if (successful) {
            diagSuccess.incrementAndGet()
            diagLastSuccessAtMs.set(android.os.SystemClock.elapsedRealtime())
        } else {
            diagFailures.incrementAndGet()
            DiagnosticLog.event("PID_POLL_FAILURE", "pid=${gauge.pid} label=${gauge.display.label}")
        }
        if (successful) failedPolls.remove(gauge)
        else failedPolls.compute(gauge) { _, count -> ((count ?: 0) + 1).coerceAtMost(3) }
        commFaultState.value = data.values.any { (failedPolls[it] ?: 0) >= 3 }
    }
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

    private fun cancelPendingMinimums() {
        synchronized(pendingMinimums) {
            pendingMinimums.values.forEach { it.task?.cancel(false) }
            pendingMinimums.clear()
        }
    }

    fun populateQuery(pos: Int, screen: Int, query: Display): TorqueData {
        if (activeScreen != screen) {
            cancelPendingMinimums()
            activeScreen = screen
        }
        data[pos]?.let { old ->
            synchronized(pendingMinimums) {
                pendingMinimums.remove(old)?.task?.cancel(false)
            }
        }
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
        failedPolls.clear()
        commFaultState.value = false
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
                            markPollResult(torqueData, false)
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
        val queried = service.runIfConnected { ts ->
            val requestStarted = android.os.SystemClock.elapsedRealtime()
            val value = try {
                ts.getPIDValuesAsDouble(arrayOf(torqueData.pid!!))[0]
            } catch (e: ArrayIndexOutOfBoundsException) {
                Timber.e("Torque returned invalid data for ${torqueData.pid}")
                markPollResult(torqueData, false)
                DiagnosticLog.event("PID_RESPONSE_MISSING", "pid=${torqueData.pid}")
                return@runIfConnected
            } finally {
                diagLastResponseMs.set(android.os.SystemClock.elapsedRealtime() - requestStarted)
            }
            if (!torqueData.updateReading(value)) {
                DiagnosticLog.event("PID_REJECTED", "pid=${torqueData.pid} raw=$value")
                markPollResult(torqueData, false)
                return@runIfConnected
            }
            markPollResult(torqueData, true)
            torqueData.hasReceivedReading = true
            if (data[7] === torqueData) {
                if (!torqueData.display.hasHorizontalDailyMaxEnabled() || torqueData.display.horizontalDailyMaxEnabled) {
                    DailyPeakStore.recordMaximum(appContext, torqueData.display, torqueData.lastData, horizontalBar = true)
                }
            } else when (torqueData.display.maxMarksActive) {
                MaxControl.MAX -> DailyPeakStore.recordMaximum(appContext, torqueData.display, torqueData.lastData)
                MaxControl.MIN -> validateMinimumIfNeeded(service, torqueData, torqueData.lastData)
                MaxControl.BOTH -> {
                    DailyPeakStore.recordMaximum(appContext, torqueData.display, torqueData.lastData)
                    validateMinimumIfNeeded(service, torqueData, torqueData.lastData)
                }
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
        if (!queried) markPollResult(torqueData, false)
    }

    /**
     * Validate each new daily minimum over one fixed five-second window.
     * Subsequent lower readings update the candidate without resetting its timer.
     * RPM is checked only at the beginning and end of the window.
     */
    private fun validateMinimumIfNeeded(service: TorqueService, gauge: TorqueData, candidate: Double) {
        if (!pollingEnabled) return
        synchronized(pendingMinimums) {
            // Existing windows consume only readings from this gauge's normal PID polling.
            val existing = pendingMinimums[gauge]
            if (existing != null) {
                if (existing.collecting && candidate.isFinite()) {
                    val previous = existing.lowest
                    if (previous == null || candidate < previous) {
                        existing.lowest = candidate
                        DiagnosticLog.event("MIN_CANDIDATE", "pid=${gauge.pid} value=$candidate")
                    }
                }
                return
            }
            if (!DailyPeakStore.isNewMinimum(appContext, gauge.display, candidate)) return
            val date = LocalDate.now().toString()
            // The triggering reading is deliberately NOT a validated candidate.
            DiagnosticLog.event("MIN_TRIGGER", "pid=${gauge.pid} candidate=$candidate")
            val pending = PendingMinimum(date)
            pendingMinimums[gauge] = pending
            pending.task = rpmValidationExecutor.schedule(initialCheck@{
                try {
                    var rpmValid = false
                    service.runIfConnected { ts ->
                        val rpm = ts.getPIDValuesAsDouble(arrayOf("0c,0")).firstOrNull()
                        rpmValid = rpm != null && rpm.isFinite() && rpm > 300.0
                        DiagnosticLog.event("MIN_RPM_INITIAL", "pid=${gauge.pid} rpm=$rpm valid=$rpmValid ecu=${ts.isConnectedToECU}")
                    }
                    synchronized(pendingMinimums) {
                        if (pendingMinimums[gauge] !== pending) return@initialCheck
                        if (!rpmValid || !pollingEnabled || activeService !== service ||
                            gauge !in data.values || date != LocalDate.now().toString()) {
                            DiagnosticLog.event("MIN_REJECT", "pid=${gauge.pid} reason=initial_rpm_or_state rpmValid=$rpmValid")
                            pendingMinimums.remove(gauge)
                            return@initialCheck
                        }
                        // Only subsequent normal polling samples can initialize lowest.
                        pending.collecting = true
                        pending.task = rpmValidationExecutor.schedule({
                            try {
                                // Atomically close the sampling window before the final RPM IPC.
                                val minimum = synchronized(pendingMinimums) {
                                    if (pendingMinimums[gauge] !== pending || !pending.collecting) null
                                    else {
                                        pending.collecting = false
                                        pending.lowest
                                    }
                                }
                                if (minimum != null) {
                                    var finalRpmValid = false
                                    service.runIfConnected { ts ->
                                        val rpm = ts.getPIDValuesAsDouble(arrayOf("0c,0")).firstOrNull()
                                        finalRpmValid = rpm != null && rpm.isFinite() && rpm > 300.0
                                        DiagnosticLog.event("MIN_RPM_FINAL", "pid=${gauge.pid} rpm=$rpm valid=$finalRpmValid ecu=${ts.isConnectedToECU} candidate=$minimum")
                                    }
                                    synchronized(pendingMinimums) {
                                        if (!finalRpmValid) DiagnosticLog.event("MIN_REJECT", "pid=${gauge.pid} value=$minimum reason=final_rpm")
                                        if (finalRpmValid && pendingMinimums[gauge] === pending &&
                                            pollingEnabled && activeService === service &&
                                            gauge in data.values && date == LocalDate.now().toString()) {
                                            DiagnosticLog.event("MIN_ACCEPT", "pid=${gauge.pid} value=$minimum")
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
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Initial RPM minimum validation failed; candidate rejected")
                    synchronized(pendingMinimums) {
                        if (pendingMinimums[gauge] === pending) pendingMinimums.remove(gauge)
                    }
                }
            }, 0, TimeUnit.MILLISECONDS)
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
        failedPolls.clear()
        commFaultState.value = false
        cancelPendingMinimums()
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
