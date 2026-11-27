package com.aatorque.stats

import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Separate 200 ms recording clock; no IPC and no disk access here.
 * Only the lifecycle owner starts/stops this recorder. */
class AplRecorder(private val writer: AplLogWriter, private val triggerVerified: (Long) -> Boolean = { false }) {
    enum class State { WAITING, INITIALIZING, ARMED, RECORDING, SAVED }
    data class Row(val elapsedMs: Long, val wallMs: Long,
                   val samples: Map<String, PidSampleCache.Sample>)
    data class Event(val session: Long, val triggerMs: Long, val triggerWallMs: Long,
                     val reason: String, val rows: List<Row>)

    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "V-Sport-APL-recorder").apply { isDaemon = true }
    }
    private val lock = Any()
    private var task: ScheduledFuture<*>? = null
    private var session: Long? = null
    private val ring = ArrayDeque<Row>(50)
    private var event: ArrayList<Row>? = null
    private var triggerMs = 0L
    private var triggerWallMs = 0L
    private var saved = false
    private var count = 0
    private var failures = 0
    private var lastSaved: Long? = null
    private var ticks = 0L
    private var firstTick = 0L
    private var readinessReason = "Filling pre-trigger buffer"
    private var loggedReadiness = ""
    data class Stats(val buffer: Int, val durationMs: Long?, val count: Int, val failures: Int,
                     val lastSaved: Long?, val hz: Double?, val reason: String)
    fun stats(): Stats = synchronized(lock) {
        Stats(ring.size, if (event != null) (lastTick - triggerMs).coerceAtLeast(0L) else null,
            count, failures, lastSaved, if (ticks > 1 && lastTick > firstTick)
                (ticks - 1) * 1000.0 / (lastTick - firstTick) else null, readinessReason)
    }
    private var lastTick = 0L
    private val detector = AplEventDetector()
    @Volatile var state = State.WAITING
        private set

    fun start(generation: Long) = synchronized(lock) {
        if (session == generation) return@synchronized
        stopLocked("session_changed")
        session = generation
        state = State.INITIALIZING
        task = worker.scheduleAtFixedRate({
            synchronized(lock) {
                if (session == generation) {
                    try { tick(generation) }
                    catch (e: Exception) {
                        DiagnosticLog.event("APL_RECORDER_ERROR", e.javaClass.simpleName)
                        stopLocked("recorder_error")
                    }
                }
            }
        }, 0, 200, TimeUnit.MILLISECONDS)
    }

    private fun input(row: Row): AplEventDetector.Input? {
        fun fresh(id: String): PidSampleCache.Sample? {
            val s = row.samples[id] ?: return null
            val age = s.ageMs(row.elapsedMs) ?: return null
            return s.takeIf { it.quality != PidValidator.Quality.SUSPECT &&
                it.sourceToken > 0 && age <= 400L && it.value?.isFinite() == true }
        }
        val rpm = fresh(AplPidInventory.RPM) ?: return null
        val pedal = fresh("49,0") ?: return null
        val load = fresh("04,0") ?: return null
        return AplEventDetector.Input(rpm.value!!, pedal.value!!, load.value!!,
            rpm.sourceToken, pedal.sourceToken, load.sourceToken)
    }

    private fun tick(generation: Long) {
        if (PidSampleCache.generation() != generation) return
        val now = SystemClock.elapsedRealtime()
        // Scheduled executor catch-up must never fabricate multiple rows.
        if (lastTick > 0L && now - lastTick < 150L) return
        if (lastTick > 0L && now - lastTick > 400L) {
            ring.clear()
            if (!detector.recording) detector.reset()
        }
        lastTick = now
        if (ticks == 0L) firstTick = now
        ticks++
        val row = Row(now, System.currentTimeMillis(), PidSampleCache.snapshot(generation))
        val signal = if (triggerVerified(generation)) input(row) else null
        // Only trigger inputs require 400 ms freshness. Optional telemetry may have
        // slower native update rates (GPS/gear), and is quality-tagged in every CSV row.
        val bufferReady = ring.size == 50 && now - ring.first().elapsedMs >= 9_500L
        val ready = bufferReady && signal != null
        readinessReason = when {
            !triggerVerified(generation) -> "Waiting for trigger PID metadata"
            signal == null -> "Waiting for fresh RPM, pedal or load"
            !bufferReady -> "Filling pre-trigger buffer"
            else -> "Trigger ready"
        }
        if (readinessReason != loggedReadiness) {
            loggedReadiness = readinessReason
            DiagnosticLog.event("APL_READINESS", "reason=$readinessReason buffer=${ring.size}")
        }
        val existing = event
        if (existing != null && existing.size < 660) existing.add(row)
        when (detector.update(now, signal, ready)) {
            AplEventDetector.Action.START -> {
                triggerMs = now
                triggerWallMs = row.wallMs
                event = ArrayList<Row>(660).apply {
                    addAll(ring)
                    add(row)
                }
                DiagnosticLog.event("APL_TRIGGER", "session=$generation")
            }
            AplEventDetector.Action.FINISH -> finishLocked("event_complete")
            AplEventDetector.Action.NONE -> Unit
        }
        if (event?.size == 660) {
            finishLocked("capacity_limit")
            detector.capacityReached()
        }
        if (ring.size == 50) ring.removeFirst()
        ring.addLast(row)
        val nextState = when {
            event != null -> State.RECORDING
            !ready -> State.INITIALIZING
            saved -> State.SAVED
            else -> State.ARMED
        }
        if (nextState != state) {
            DiagnosticLog.event("APL_STATE", "old=$state new=$nextState reason=$readinessReason")
            state = nextState
        }
    }

    private fun finishLocked(reason: String) {
        val rows = event ?: return
        val generation = session ?: return
        event = null
        writer.submit(Event(generation, triggerMs, triggerWallMs, reason, rows)) { success ->
            synchronized(lock) {
                if (session == generation) {
                    if (success) { saved = true; count++; lastSaved = System.currentTimeMillis() }
                    else failures++
                }
            }
        }
    }

    private fun stopLocked(reason: String) {
        task?.cancel(false)
        task = null
        finishLocked(reason)
        ring.clear()
        event = null
        detector.reset()
        session = null
        lastTick = 0L
        saved = false
        count = 0
        failures = 0
        lastSaved = null
        ticks = 0
        firstTick = 0
        state = State.WAITING
    }

    fun stop(reason: String) = synchronized(lock) { stopLocked(reason) }
    fun close() {
        stop("app_stopped")
        worker.shutdown()
        // Writer is owned by the service controller and drains across ECU sessions.
    }
}
