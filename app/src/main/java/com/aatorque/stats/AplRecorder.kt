package com.aatorque.stats

import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Separate 200 ms recording clock; no IPC and no disk access here.
 * Only the lifecycle owner starts/stops this recorder. */
class AplRecorder(private val writer: AplLogWriter) {
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
        val row = Row(now, System.currentTimeMillis(), PidSampleCache.snapshot(generation))
        val signal = input(row)
        val ready = ring.size == 50 && now - ring.first().elapsedMs >= 9_800L && signal != null
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
            detector.reset()
        }
        if (ring.size == 50) ring.removeFirst()
        ring.addLast(row)
        state = when {
            event != null -> State.RECORDING
            !ready -> State.INITIALIZING
            saved -> State.SAVED
            else -> State.ARMED
        }
    }

    private fun finishLocked(reason: String) {
        val rows = event ?: return
        val generation = session ?: return
        event = null
        writer.submit(Event(generation, triggerMs, triggerWallMs, reason, rows)) { success ->
            synchronized(lock) {
                if (session == generation && success) saved = true
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
        state = State.WAITING
    }

    fun stop(reason: String) = synchronized(lock) { stopLocked(reason) }
    fun close() {
        stop("app_stopped")
        worker.shutdown()
        writer.close()
    }
}
