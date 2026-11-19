package com.aatorque.stats

/**
 * Exclusive ownership of performance PID polling within this application process.
 * The background service remains responsible for its own ECU connection checks.
 */
object PerformancePollingOwnership {
    @Volatile private var foreground = false
    @Volatile private var backgroundStop: (() -> Unit)? = null

    @Synchronized fun foregroundOpened() {
        foreground = true
        backgroundStop?.invoke()
    }

    @Synchronized fun foregroundClosed() {
        foreground = false
    }

    fun isForeground(): Boolean = foreground

    @Synchronized fun registerBackgroundStop(stop: () -> Unit) {
        backgroundStop = stop
        if (foreground) stop()
    }

    @Synchronized fun unregisterBackgroundStop() {
        backgroundStop = null
    }
}
