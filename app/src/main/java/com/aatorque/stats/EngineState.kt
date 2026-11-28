package com.aatorque.stats

/** Independent of APL session lifetime. A repeated cached RPM never renews its age. */
object EngineState {
    enum class State { DISCONNECTED, UNKNOWN, OFF, RUNNING }
    private var connected = false
    private var token = 0L
    private var rpm = 0.0
    private var runningCount = 0
    private var observedAt = 0L
    private var sourceAge = 0L
    @Synchronized fun connection(value: Boolean) {
        if (connected == value) return
        connected = value
        token = 0L
        runningCount = 0
        observedAt = 0L
    }
    @Synchronized fun observe(value: Double?, timestamp: Long, now: Long, wall: Long) {
        if (!connected || value == null || !value.isFinite() || value !in 0.0..7000.0 ||
            timestamp < 1_000_000_000_000L || timestamp > wall || wall - timestamp > 2000L ||
            timestamp <= token) return
        if (token == 0L || now - observedAt + sourceAge > 5000L) runningCount = 0
        token = timestamp
        rpm = value
        observedAt = now
        sourceAge = wall - timestamp
        runningCount = if (value > 300.0) (runningCount + 1).coerceAtMost(2) else 0
    }
    @Synchronized fun state(now: Long = android.os.SystemClock.elapsedRealtime()): State {
        if (!connected) return State.DISCONNECTED
        if (token == 0L || now - observedAt + sourceAge > 5000L) return State.UNKNOWN
        return when {
            rpm < 300.0 -> State.OFF
            rpm > 300.0 && runningCount >= 2 -> State.RUNNING
            else -> State.UNKNOWN
        }
    }
}
