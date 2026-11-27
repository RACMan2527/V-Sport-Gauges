package com.aatorque.stats

/** Compare actual gauge latency, usable throughput and expiration, not cached repeats. */
object AplPollingGuard {
    private data class Observation(val owner: String, val latency: Long, val usable: Boolean, val expired: Boolean)
    private data class Window(val owners: Set<String>, val period: Long, val latency: Double,
                              val rate: Double, val expired: Double, val failed: Double)
    private val lock = Any()
    private val observations = ArrayList<Observation>()
    private var baseline: Window? = null
    private var active = false
    private var windowAt = 0L
    private var nextAllowed = 0L
    private var level = 0
    private var healthy = 0
    private var lastMode = ""
    private const val WINDOW = 10_000L

    fun observeGauge(owner: String, latencyMs: Long, usable: Boolean, expired: Boolean) = synchronized(lock) {
        if (active && observations.size < 8000)
            observations.add(Observation(owner, latencyMs.coerceAtLeast(0L), usable, expired))
    }
    fun start(now: Long) = synchronized(lock) {
        observations.clear()
        baseline = null
        active = true
        windowAt = now
        nextAllowed = now + WINDOW
        level = 0
        healthy = 0
        lastMode = ""
    }
    fun stop() = synchronized(lock) { active = false; observations.clear() }
    data class Decision(val allowed: Boolean, val batch: Boolean, val reason: String)

    fun decide(now: Long): Decision = synchronized(lock) {
        if (!active) return@synchronized Decision(false, false, "inactive")
        if (now - windowAt >= WINDOW) {
            val elapsed = now - windowAt
            windowAt = now
            val count = observations.size
            val successful = observations.count { it.usable }
            val current = if (count >= 20) Window(
                observations.map { it.owner }.toSet(), TorqueRefresher.SAMPLE_INTERVAL,
                observations.map { it.latency }.average(), successful * 1000.0 / elapsed,
                observations.count { it.expired }.toDouble() / count,
                (count - successful).toDouble() / count
            ) else null
            observations.clear()
            val old = baseline
            if (current == null) {
                if (old != null) { level = 2; healthy = 0; nextAllowed = now + WINDOW }
                else { level = 1; healthy = 0 }
            } else if (old == null || old.owners != current.owners || old.period != current.period) {
                if (old != null || level == 1) {
                    baseline = null
                    level = 0
                    nextAllowed = now + WINDOW
                    healthy = 0
                    DiagnosticLog.event("APL_GUARD_REBASE", "owners=${current.owners.size} period=${current.period}")
                } else {
                    baseline = current
                    level = 0
                    healthy = 0
                    DiagnosticLog.event("APL_GUARD_BASELINE", "latencyMs=${current.latency} rate=${current.rate} expired=${current.expired} failed=${current.failed}")
                }
            } else {
                val degraded = (current.latency > old.latency * 1.10 && current.latency > old.latency + 2.0) ||
                    current.rate < old.rate * 0.90 || current.expired > old.expired + 0.02 ||
                    current.failed > old.failed + 0.02
                if (degraded) {
                    healthy = 0
                    level = (level + 1).coerceAtMost(2)
                    nextAllowed = now + if (level == 2) WINDOW else 1000L
                } else {
                    healthy++
                    if (healthy >= 2 && level > 0) {
                        level--
                        healthy = 0
                        nextAllowed = now
                    }
                }
                DiagnosticLog.event("APL_GUARD_WINDOW", "baselineMs=${old.latency} latencyMs=${current.latency} rate=${current.rate} expired=${current.expired} failed=${current.failed} level=$level")
            }
        }
        val decision = when {
            level == 2 -> Decision(false, false, "suspended")
            now < nextAllowed -> Decision(false, false, if (baseline == null) "baseline" else "backoff")
            level == 1 || baseline == null -> Decision(true, false, "reduced")
            else -> Decision(true, true, "normal")
        }
        if (decision.reason != lastMode) {
            lastMode = decision.reason
            DiagnosticLog.event("APL_GUARD_STATE", "mode=$lastMode")
        }
        decision
    }

    fun batchFailed(now: Long) = synchronized(lock) {
        if (!active) return@synchronized
        level = maxOf(level, 1)
        healthy = 0
        nextAllowed = maxOf(nextAllowed, now + 400L)
    }
    fun completed(now: Long, duration: Long, batch: Boolean) {
        if (duration > 200L) batchFailed(now)
    }
}
