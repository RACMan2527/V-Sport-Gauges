package com.aatorque.stats

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Process-wide status only; the foreground service owns actual worker instances. */
object AplStatus {
    private const val PREFS = "apl_settings"
    private const val ENABLED = "enabled"
    @Volatile internal var recorder: AplRecorder? = null
    @Volatile internal var acquisition: AplAcquisition? = null
    fun recorderStats() = recorder?.stats()
    fun acquisitionStats() = acquisition?.stats()
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)
    @Volatile internal var engineStandby = false
    fun state(): AplRecorder.State = recorder?.state ?: AplRecorder.State.WAITING
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        requestService(context)
    }
    fun requestService(context: Context) {
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, BackgroundFuelService::class.java))
        } catch (e: Exception) {
            DiagnosticLog.event("APL_SERVICE_START_FAILED", e.javaClass.simpleName)
        }
    }
}

/** One owner per foreground service; dashboard visibility never owns APL. */
class AplController(private val context: Context, private val torque: TorqueService) {
    private var generation: Long? = null
    private var acquisition: AplAcquisition? = null
    private var recorder: AplRecorder? = null
    private val writer by lazy { AplLogWriter(context) }
    private var writerUsed = false

    private var lastEngineState: EngineState.State? = null
    private var lastFallbackAt = -2000L
    private val rpmWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val fallbackBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    fun update(connected: Boolean, fuelAvailable: Boolean) {
        val enabled = AplStatus.enabled(context)
        if (!enabled || !connected || !PublicDocuments.hasFolderAccess(context)) {
            AplStatus.engineStandby = false
            lastEngineState = null
            stop(if (!enabled) "disabled" else if (!connected) "ecu_disconnected" else "folder_unavailable")
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val engine = EngineState.state(now)
        if (!fuelAvailable && now - lastFallbackAt >= 2000L && fallbackBusy.compareAndSet(false, true)) {
            lastFallbackAt = now
            rpmWorker.execute {
                try {
                    val ts = torque.torqueService
                    if (ts != null && ts.isConnectedToECU) {
                        val rpm = ts.getPIDValuesAsDouble(arrayOf("0c,0")).firstOrNull()
                        val token = ts.getPIDUpdateTime(arrayOf("0c,0")).firstOrNull() ?: 0L
                        if (ts.isConnectedToECU) EngineState.observe(rpm, token,
                            android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())
                    }
                } catch (_: Exception) { } finally { fallbackBusy.set(false) }
            }
        }
        if (lastEngineState != engine) {
            DiagnosticLog.event("APL_ENGINE_STATE", "old=$lastEngineState new=$engine")
            lastEngineState = engine
        }
        AplStatus.engineStandby = engine != EngineState.State.RUNNING
        if (engine != EngineState.State.RUNNING) {
            stop(if (engine == EngineState.State.OFF) "engine_off" else "engine_unknown")
            return
        }
        if (generation != null) return
        val session = PidSampleCache.beginSession()
        generation = session
        val acquire = AplAcquisition(torque)
        writerUsed = true
        val record = AplRecorder(writer, acquire::triggerVerified)
        acquisition = acquire
        recorder = record
        AplStatus.recorder = record
        AplStatus.acquisition = acquire
        acquire.start(session)
        record.start(session)
        DiagnosticLog.event("APL_SESSION_START", "session=$session")
    }

    fun close() {
        rpmWorker.shutdownNow()
        AplStatus.engineStandby = false
        stop("app_stopped")
        if (writerUsed) writer.close()
    }

    fun stop(reason: String) {
        val session = generation ?: return
        generation = null
        if (AplStatus.acquisition === acquisition) AplStatus.acquisition = null
        acquisition?.close()
        acquisition = null
        recorder?.stop(reason)
        recorder?.close()
        if (AplStatus.recorder === recorder) AplStatus.recorder = null
        recorder = null
        PidSampleCache.endSession(session)
        DiagnosticLog.event("APL_SESSION_END", "session=$session reason=$reason")
    }
}
