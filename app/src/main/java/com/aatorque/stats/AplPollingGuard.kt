package com.aatorque.stats

import android.os.SystemClock

/** Passive gauge latency measurements; no locks held across Torque IPC.
 * Baseline is collected with supplemental acquisition inactive. */
object AplPollingGuard {
    private data class Observation(val at: Long, val latency: Long, val stale: Boolean)
    private val lock = Any()
    private val observations = ArrayDeque<Observation>()
    private var baselineMs: Double? = null
    private var baselineRate: Double? = null
    private var baselineStale: Double? = null
    private var started = 0L
    private var active = false
    private var fallback = false
    private var nextAllowed = 0L
    private var windowAt = 0L
    private const val WINDOW = 10_000L

    fun observeGauge(latencyMs: Long, stale: Boolean) = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        observations.addLast(Observation(now, latencyMs.coerceAtLeast(0L), stale))
        while (observations.isNotEmpty() &&
            (now - observations.first().at > WINDOW || observations.size > 4000)) observations.removeFirst()
    }

    fun start(now: Long) = synchronized(lock) {
        observations.clear()
        baselineMs = null
        baselineRate = null
        baselineStale = null
        started = now
        active = true
        fallback = false
        nextAllowed = now
        windowAt = now
    }

    fun stop() = synchronized(lock) { active = false }
    data class Decision(val allowed: Boolean, val batch: Boolean, val reason: String)

    fun decide(now: Long): Decision = synchronized(lock) {
        if (!active) return@synchronized Decision(false, false, "inactive")
        while (observations.isNotEmpty() && now - observations.first().at > WINDOW) observations.removeFirst()
        if (now - started < WINDOW) return@synchronized Decision(false, false, "baseline")
        val enough = observations.size >= 20
        val mean = if (enough) observations.map { it.latency }.average() else null
        val rate = observations.size / 10.0
        val stale = if (enough) observations.count { it.stale }.toDouble() / observations.size else 0.0
        if (baselineMs == null && mean != null) {
            baselineMs = mean.coerceAtLeast(1.0)
            baselineRate = rate
            baselineStale = stale
        } else if (now - windowAt >= WINDOW && mean != null && baselineMs != null) {
            val slower = mean > baselineMs!! * 1.10 && mean > baselineMs!! + 2.0
            val lowerRate = rate < (baselineRate ?: rate) * 0.95
            val moreStale = stale > (baselineStale ?: stale) + 0.02
            if (slower || lowerRate || moreStale) {
                fallback = true
                nextAllowed = now + 2_000L
            }
        }
        if (now - windowAt >= WINDOW) windowAt = now
        if (now < nextAllowed) Decision(false, !fallback, "backoff")
        else Decision(true, !fallback, if (fallback) "single" else "batch")
    }

    fun batchFailed(now: Long) = synchronized(lock) {
        fallback = true
        nextAllowed = now + 400L
    }
    fun completed(now: Long, duration: Long, batch: Boolean) = synchronized(lock) {
        if (batch && duration > 200L) {
            fallback = true
            nextAllowed = now + 400L
        } else if (fallback) nextAllowed = now + 200L
    }
}
