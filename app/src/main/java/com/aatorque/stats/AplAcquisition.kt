package com.aatorque.stats

import android.os.SystemClock
import org.prowl.torque.remote.ITorqueService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One supplemental worker, fixed delay, no request backlog and no gauge-thread waits.
 * Recording has its own clock; lifecycle owner supplies an active cache generation. */
class AplAcquisition(private val torque: TorqueService) {
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "V-Sport-APL-acquisition").apply { isDaemon = true }
    }
    @Volatile private var generation: Long? = null
    private var task: ScheduledFuture<*>? = null
    @Volatile private var metadataGeneration: Long? = null
    @Volatile private var verified = emptySet<String>()
    fun triggerVerified(session: Long): Boolean = metadataGeneration == session &&
        verified.containsAll(listOf(AplPidInventory.RPM, "49,0", "04,0"))
    @Volatile private var inventory = emptyMap<String, String>()
    private var lastVerifyAt = 0L
    private var cursor = 0
    @Volatile private var requested = 0L
    @Volatile private var calls = 0L
    @Volatile private var skipped = 0L
    @Volatile private var failures = 0L
    private var lastReport = 0L
    @Volatile private var recentRate = 0.0
    private var rateAt = SystemClock.elapsedRealtime()
    private var rateCount = 0L
    private var optionalAt = 0L
    private val lastAttempt = HashMap<String, Long>()
    private var metricsAt = SystemClock.elapsedRealtime()
    private fun updateRate(now: Long) {
        if (now - rateAt >= 1000L) {
            recentRate = (requested - rateCount) * 1000.0 / (now - rateAt)
            rateCount = requested
            rateAt = now
        }
    }
    @Volatile private var mode = "baseline"
    data class Stats(val mode: String, val requestsPerSec: Double, val calls: Long, val skipped: Long, val failures: Long, val inventory: Map<String, String>)
    fun stats() = Stats(mode, recentRate,
        calls, skipped, failures, inventory)

    @Synchronized fun start(session: Long) {
        if (generation == session && task != null) return
        stop()
        generation = session
        AplPollingGuard.start(SystemClock.elapsedRealtime())
        task = worker.scheduleWithFixedDelay({
            try { cycle(session) }
            catch (e: Exception) {
                failures++
                AplPollingGuard.batchFailed(SystemClock.elapsedRealtime())
            }
        }, 0L, 100L, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun stop() {
        generation = null
        task?.cancel(false)
        task = null
        AplPollingGuard.stop()
    }

    @Synchronized fun close() {
        stop()
        worker.shutdown()
    }

    private fun current(session: Long): Boolean =
        generation == session && PidSampleCache.generation() == session

    private fun verify(ts: ITorqueService, session: Long) {
        val ids = ts.listAllPIDs()
        val wanted = AplPidInventory.requirements.map { it.id }.filter { it in ids }.toTypedArray()
        val info = ts.getPIDInformation(wanted)
        val statuses = AplPidInventory.verify(wanted.toList(), info.toList())
        if (!current(session)) return
        verified = AplPidInventory.requirements.filter {
            statuses[it.key] == "metadata_present"
        }.map { it.id }.toSet()
        inventory = statuses
        lastVerifyAt = SystemClock.elapsedRealtime()
        metadataGeneration = session
        DiagnosticLog.event("APL_PID_INVENTORY", statuses.entries.joinToString(" ") { it.key + "=" + it.value })
    }

    private fun cycle(session: Long) {
        if (!current(session)) return
        val ts = torque.torqueService ?: return
        if (!ts.isConnectedToECU) return
        val now = SystemClock.elapsedRealtime()
        updateRate(now)
        val decision = AplPollingGuard.decide(now)
        mode = decision.reason
        if (!decision.allowed) { skipped++; report(now, decision.reason); return }
        if (metadataGeneration != session || (!triggerVerified(session) && now - lastVerifyAt >= 10_000L)) {
            verify(ts, session)
            return
        }
        val samples = PidSampleCache.snapshot(session)
        val due = AplPidInventory.requirements.map { it.id }.filter { id ->
            if (id !in verified || PidSampleCache.regularPolling(session, id, now, TorqueRefresher.SAMPLE_INTERVAL)) false
            else {
                val sample = samples[id]
                val interval = sample?.sourceIntervalMs
                val cadence = if (id in AplPidInventory.triggerIds) (interval?.div(3) ?: 100L).coerceIn(100L, 250L)
                    else (interval?.div(2) ?: 500L).coerceIn(200L, 1000L)
                val age = sample?.ageMs(now)
                val needs = sample == null || sample.quality == PidValidator.Quality.SUSPECT ||
                    age == null || age >= cadence
                needs && now - (lastAttempt[id] ?: -10_000L) >= cadence
            }
        }
        val selected = due.filter { it in AplPidInventory.triggerIds }.toMutableList()
        val optional = due.filter { it !in AplPidInventory.triggerIds }
            .sortedBy { lastAttempt[it] ?: Long.MIN_VALUE }
        val optionalPeriod = if (decision.batch) 200L else 1000L
        if (optional.isNotEmpty() && now - optionalAt >= optionalPeriod) {
            selected.addAll(optional.take(if (decision.batch) 2 else 1))
            optionalAt = now
        }
        if (selected.isEmpty()) { report(now, "cache"); return }
        selected.removeAll { PidSampleCache.regularPolling(session, it, SystemClock.elapsedRealtime(), TorqueRefresher.SAMPLE_INTERVAL) }
        if (selected.isEmpty()) { report(now, "cache"); return }
        val ids = selected.toTypedArray()
        val started = SystemClock.elapsedRealtime()
        val batch = ids.size > 1
        try {
            if (!current(session)) return
            for (id in ids) {
                lastAttempt[id] = started
                PidSampleCache.request(session, id, "apl", started)
            }
            calls++
            requested += ids.size
            val before = if (ids.any { it in AplPidInventory.triggerIds }) ts.getPIDUpdateTime(ids) else null
            val values = ts.getPIDValuesAsDouble(ids)
            val tokens = ts.getPIDUpdateTime(ids)
            if (values.size != ids.size || tokens.size != ids.size || (before != null && before.size != ids.size)) {
                failures++
                AplPollingGuard.batchFailed(SystemClock.elapsedRealtime())
                return
            }
            if (!current(session) || !ts.isConnectedToECU) return
            for (i in ids.indices) {
                val pid = ids[i]
                val value = values[i]
                val token = tokens[i]
                if (pid in AplPidInventory.triggerIds && before != null && before[i] != token) continue
                val bounds = PidValidator.bounds(pid)
                val acceptable = value.isFinite() && kotlin.math.abs(value) < Float.MAX_VALUE.toDouble() &&
                    token > 0L && (bounds == null ||
                    value >= bounds.minimum - 1.0 && value <= bounds.maximum + 1.0) &&
                    (pid != AplPidInventory.RPM || value in 0.0..7000.0) &&
                    (pid !in setOf("49,0", "11,0", "04,0") || value in 0.0..100.0)
                if (acceptable && pid == AplPidInventory.RPM) EngineState.observe(value, token,
                    SystemClock.elapsedRealtime(), System.currentTimeMillis())
                PidSampleCache.publish(session, pid, value, token,
                    if (acceptable) PidValidator.Quality.VALID else PidValidator.Quality.SUSPECT)
            }
        } catch (e: Exception) {
            failures++
            AplPollingGuard.batchFailed(SystemClock.elapsedRealtime())
        } finally {
            val ended = SystemClock.elapsedRealtime()
            if (current(session)) {
                ids.forEach { PidSampleCache.completed(session, it, ended - started) }
                AplPollingGuard.completed(ended, ended - started, batch)
            }
            updateRate(ended)
            report(ended, decision.reason)
        }
    }

    private fun report(now: Long, mode: String) {
        val session = generation
        if (session != null && now - metricsAt >= 60_000L) {
            PidSampleCache.drainMetrics(session, now, now - metricsAt).forEach { DiagnosticLog.event("APL_PID_METRICS", it) }
            metricsAt = now
        }
        if (now - lastReport < 60_000L) return
        lastReport = now
        DiagnosticLog.event("APL_ACQUISITION", "mode=$mode calls=$calls requested=$requested skipped=$skipped failures=$failures")
    }
}
