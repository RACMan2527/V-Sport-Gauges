package com.aatorque.stats

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.aatorque.prefs.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Phone foreground service: independent of Android Auto dashboard lifecycle. */
class BackgroundFuelService : Service() {
    private val torque = TorqueService(quietReconnectLogs = true)
    private val performanceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val performance by lazy { BackgroundPerformancePoller(this, torque, performanceScope) }
    private val handler = Handler(Looper.getMainLooper())
    private var lastState: String? = null
    private var attemptedAt = 0L
    private val check = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val api = torque.torqueService
            val state = when {
                api == null -> "TORQUE_UNAVAILABLE"
                try { api.isConnectedToECU } catch (_: Exception) { false } -> "ECU_CONNECTED"
                else -> "ECU_DISCONNECTED"
            }
            if (state == "ECU_CONNECTED" && !PerformancePollingOwnership.isForeground()) performance.start()
            else performance.stop()
            BatteryStats.sample(this@BackgroundFuelService, monitoring = true,
                ecuDisconnected = state != "ECU_CONNECTED")
            if (state != lastState) {
                DiagnosticLog.event("BACKGROUND_CONNECTION", "old=${lastState ?: "START"} new=$state")
                lastState = state
            }
            if (api == null && now - attemptedAt >= 30_000L) {
                attemptedAt = now
                if (torque.hasBound) torque.onDestroy(this@BackgroundFuelService)
                try { torque.startTorque(this@BackgroundFuelService) }
                catch (e: Exception) {
                    if (lastState != "TORQUE_UNAVAILABLE") DiagnosticLog.event("BACKGROUND_TORQUE_BIND_ERROR", e.javaClass.simpleName)
                }
            }
            handler.postDelayed(this, 5_000L)
        }
    }
    private val batteryReport = object : Runnable {
        override fun run() {
            DiagnosticLog.recordBattery(this@BackgroundFuelService, "background_hourly")
            handler.postDelayed(this, 3_600_000L)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            "fuel_monitor", "Fuel and trip monitoring", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "fuel_monitor")
            .setContentTitle("V-Sport fuel monitoring")
            .setContentText("Monitoring when Torque and ECU data are available")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open).setOngoing(true).build()
        startForeground(39010, notification)
        if (!getSharedPreferences("background_fuel_activation", MODE_PRIVATE)
                .getBoolean("test_dashboard_viewed", false)) {
            DiagnosticLog.event("BACKGROUND_WAITING_FOR_TEST_DASHBOARD")
            stopSelf()
            return
        }
        val invalid = listOf("Fuel-Tracker.json", "Fuel-Up-History.csv")
            .firstOrNull { PublicDocuments.fuelAccessError(this, it) != null }
        if (invalid != null) {
            DiagnosticLog.event("BACKGROUND_WAITING_FOR_FUEL_ACCESS",
                invalid + " " + PublicDocuments.fuelAccessError(this, invalid))
            stopSelf()
            return
        }
        DiagnosticLog.event("BACKGROUND_MONITOR_START")
        try {
            attemptedAt = SystemClock.elapsedRealtime()
            torque.startTorque(this)
            FuelMonitor.backgroundOpen(this, torque)
            PerformancePollingOwnership.registerBackgroundStop { performance.stop() }
            handler.post(check)
            handler.postDelayed(batteryReport, 3_600_000L)
        } catch (e: Exception) {
            DiagnosticLog.event("BACKGROUND_MONITOR_START_ERROR", "${e.javaClass.simpleName} ${e.message}")
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY
    override fun onDestroy() {
        BatteryStats.interrupted(this)
        handler.removeCallbacks(check)
        handler.removeCallbacks(batteryReport)
        PerformancePollingOwnership.unregisterBackgroundStop()
        performance.stop()
        performanceScope.cancel()
        FuelMonitor.backgroundClosed()
        torque.onDestroy(this)
        DiagnosticLog.event("BACKGROUND_MONITOR_STOP")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
