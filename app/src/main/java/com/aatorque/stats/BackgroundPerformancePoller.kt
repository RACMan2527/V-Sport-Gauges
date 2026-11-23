package com.aatorque.stats

import android.content.Context
import com.aatorque.prefs.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Polls only the saved first dashboard when Dashboard 1 is not visible. */
class BackgroundPerformancePoller(
    private val context: Context,
    private val torque: TorqueService,
    private val scope: CoroutineScope
) {
    private var refresher: TorqueRefresher? = null
    private var startup: Job? = null
    private var active = false

    fun start() {
        if (active || startup != null || !PerformancePollingOwnership.shouldPollDashboardOne()) return
        startup = scope.launch {
            try {
                val settings = context.dataStore.data.first()
                if (!PerformancePollingOwnership.shouldPollDashboardOne()) return@launch
                val first = settings.screensList.firstOrNull() ?: return@launch
                val polling = TorqueRefresher()
                polling.initialize(context)
                first.gaugesList.forEachIndexed { index, display ->
                    polling.populateQuery(index, 0, display)
                }
                first.displaysList.forEachIndexed { index, display ->
                    polling.populateQuery(index + DashboardFragment.DISPLAY_OFFSET, 0, display)
                }
                val horizontal = first.horizontalGauge
                if (!horizontal.disabled && horizontal.pid.isNotBlank()) {
                    polling.populateQuery(7, 0, horizontal)
                }
                if (!PerformancePollingOwnership.shouldPollDashboardOne()) return@launch
                refresher = polling
                active = true
                polling.makeExecutors(torque)
                DiagnosticLog.event("BACKGROUND_PERFORMANCE_START", "dashboard=1 pids=${polling.data.values.count { it.pid != null }}")
            } catch (e: Exception) {
                refresher?.stopExecutors()
                refresher = null
                active = false
                DiagnosticLog.event("BACKGROUND_PERFORMANCE_ERROR", e.javaClass.simpleName)
            } finally {
                startup = null
            }
        }
    }

    fun stop() {
        startup?.cancel()
        startup = null
        if (active) DiagnosticLog.event("BACKGROUND_PERFORMANCE_STOP")
        active = false
        refresher?.stopExecutors()
        refresher = null
    }
}
