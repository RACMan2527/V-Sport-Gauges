package com.aatorque.stats

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Process-wide status only; the foreground service owns actual worker instances. */
object AplStatus {
    private const val PREFS = "apl_settings"
    private const val ENABLED = "enabled"
    @Volatile internal var recorder: AplRecorder? = null
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)
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

    fun update(connected: Boolean) {
        val enabled = AplStatus.enabled(context)
        if (!enabled || !connected || !PublicDocuments.hasFolderAccess(context)) {
            stop(if (!enabled) "disabled" else if (!connected) "ecu_disconnected" else "folder_unavailable")
            return
        }
        if (generation != null) return
        val session = PidSampleCache.beginSession()
        generation = session
        val acquire = AplAcquisition(torque)
        val record = AplRecorder(AplLogWriter(context), acquire::triggerVerified)
        acquisition = acquire
        recorder = record
        AplStatus.recorder = record
        acquire.start(session)
        record.start(session)
        DiagnosticLog.event("APL_SESSION_START", "session=$session")
    }

    fun stop(reason: String) {
        val session = generation ?: return
        generation = null
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
