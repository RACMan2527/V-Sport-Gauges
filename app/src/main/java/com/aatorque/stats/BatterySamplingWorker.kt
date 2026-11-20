package com.aatorque.stats

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Best-effort battery sampling independent of Torque and the vehicle service. */
class BatterySamplingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            BatteryStats.sample(applicationContext, monitoring = true, ecuDisconnected = true)
            Result.success()
        } catch (e: Exception) {
            DiagnosticLog.event("BATTERY_SAMPLE_ERROR", e.javaClass.simpleName)
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BatterySamplingWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "battery_discharge_monitor", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
