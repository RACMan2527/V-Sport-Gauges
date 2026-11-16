package com.aatorque.stats

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.aatorque.prefs.SettingsActivity

/** Tasker starts this explicit foreground service while Android Auto is connected. */
class BackgroundFuelService : Service() {
    private val torque = TorqueService()
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            "fuel_monitor", "Fuel and trip monitoring", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(this, 0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "fuel_monitor")
            .setContentTitle("V-Sport fuel monitoring")
            .setContentText("Tracking tank distance, fuel and time")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(intent).setOngoing(true).build()
        startForeground(39010, notification)
        torque.startTorque(this)
        FuelMonitor.backgroundOpen(this, torque)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY
    override fun onDestroy() {
        FuelMonitor.backgroundClosed()
        torque.onDestroy(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
