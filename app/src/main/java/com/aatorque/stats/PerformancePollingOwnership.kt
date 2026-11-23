package com.aatorque.stats

/**
 * Exclusive ownership of performance PID polling within this application process.
 * The background service remains responsible for its own ECU connection checks.
 */
object PerformancePollingOwnership {
    @Volatile private var foreground = false
    @Volatile private var foregroundDashboard = 0
    @Volatile private var backgroundStop: (() -> Unit)? = null

    @Synchronized fun foregroundOpened() {
        foreground = true
        backgroundStop?.invoke()
    }

    /** Other visible dashboards do not own Dashboard 1 performance polling. */
    @Synchronized fun foregroundDashboardChanged(index: Int) {
        foregroundDashboard = index
        if (foreground && index == 0) backgroundStop?.invoke()
    }

    fun shouldPollDashboardOne(): Boolean = !foreground || foregroundDashboard != 0

    @Synchronized fun foregroundClosed() {
        foreground = false
    }

    fun isForeground(): Boolean = foreground

    @Synchronized fun registerBackgroundStop(stop: () -> Unit) {
        backgroundStop = stop
        if (foreground && foregroundDashboard == 0) stop()
    }

    @Synchronized fun unregisterBackgroundStop() {
        backgroundStop = null
    }
}
