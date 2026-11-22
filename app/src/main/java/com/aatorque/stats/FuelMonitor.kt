package com.aatorque.stats

import android.content.Context

/** Exactly one fuel sampler and file writer per app process. */
object FuelMonitor {
    private var tracker: FuelTracker? = null

    @Synchronized fun dailyValue(id: String): Double? = tracker?.dailyValue(id)
    @Synchronized fun trip3Value(id: String): Double? = tracker?.trip3Value(id)
    @Synchronized fun resetTrip3() { tracker?.resetTrip3() }
    private var dashboardSource: TorqueService? = null
    private var backgroundSource: TorqueService? = null

    @Synchronized fun dashboardOpen(context: Context, source: TorqueService): FuelTracker {
        dashboardSource = source
        return ensure(context, source)
    }

    @Synchronized fun dashboardClosed() {
        dashboardSource = null
        backgroundSource?.let { tracker?.useTorqueService(it) } ?: run {
            tracker?.close()
            tracker = null
        }
    }

    @Synchronized fun backgroundOpen(context: Context, source: TorqueService) {
        backgroundSource = source
        ensure(context, dashboardSource ?: source)
    }

    @Synchronized fun backgroundClosed() {
        backgroundSource = null
        dashboardSource?.let { tracker?.useTorqueService(it) } ?: run {
            tracker?.close()
            tracker = null
        }
    }

    private fun ensure(context: Context, source: TorqueService): FuelTracker {
        val active = tracker ?: FuelTracker(context.applicationContext, source).also {
            tracker = it
            it.start()
        }
        active.useTorqueService(source)
        return active
    }
}
