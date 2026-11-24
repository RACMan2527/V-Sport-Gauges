package com.aatorque.stats

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Build
import androidx.core.content.ContextCompat
import org.prowl.torque.remote.ITorqueService
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TorqueService(private val quietReconnectLogs: Boolean = false) {
    companion object {
        const val ACTION_SIMULATION_SETTING_CHANGED = "com.aatorque.stats.SIMULATION_SETTING_CHANGED"
    }
    var torqueService: ITorqueService? = null
    val onConnect = ArrayList<(ITorqueService) -> Unit>()
    val conLock = ReentrantLock()
    var hasBound = false
    private var connectStartedMs = 0L
    private fun elapsedMs() = android.os.SystemClock.elapsedRealtime() - connectStartedMs
    private var appContext: Context? = null
    // Registration and binding must be released through the exact same
    // Context instances, even when a preview's resource context disappears.
    private var simulationReceiverContext: Context? = null
    private var bindingContext: Context? = null
    private val simulationReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            applySimulationSetting()
        }
    }

    fun addConnectCallback(func: (ITorqueService) -> Unit): TorqueService {
        if (torqueService == null) {
            conLock.withLock {
                onConnect.add(func)
            }
        } else {
            func(torqueService!!)
        }
        return this
    }

    fun runIfConnected(func: (ITorqueService) -> Unit): Boolean {
        val service = torqueService ?: return false
        func(service)
        return true
    }

    private val torqueConnection = object : ServiceConnection {
        var forceReconnect: (()-> Unit)? = null

        /**
         * What to do when we get connected to Torque.
         *
         * @param arg0
         * @param service
         */
        override fun onServiceConnected(arg0: ComponentName, service: IBinder) {
            try {
                val svc = ITorqueService.Stub.asInterface(service)
                torqueService = svc
                if (!quietReconnectLogs) DiagnosticLog.event("TORQUE_CONNECTED", "elapsedMs=${elapsedMs()} component=$arg0")
                applySimulationSetting()
                PidValidator.resetMetadata()
                PidValidator.loadMetadataAsync(svc)
                torqueService = svc
                conLock.withLock {
                    for (funt in onConnect) {
                        funt(svc)
                    }
                    onConnect.clear()
                }
            } catch (e: DeadObjectException) {
                if (!quietReconnectLogs) DiagnosticLog.event("TORQUE_DISCONNECTED", "elapsedMs=${elapsedMs()} reason=dead_object")
                Timber.e("Disconnected from service", e)
                torqueService = null
                forceReconnect?.invoke()
            }
        }

        /**
         * What to do when we get disconnected from Torque.
         *
         * @param name
         */
        override fun onServiceDisconnected(name: ComponentName) {
            if (!quietReconnectLogs) DiagnosticLog.event("TORQUE_DISCONNECTED", "elapsedMs=${elapsedMs()} component=$name")
            torqueService = null
        }
    }

    private fun applySimulationSetting() {
        val context = appContext ?: return
        val simulateMetrics = BuildConfig.SIMULATE_METRICS ||
            context.getSharedPreferences(
                "runtime_settings", Context.MODE_PRIVATE
            ).getBoolean("simulateEcuData", false)
        torqueService?.setDebugTestMode(simulateMetrics && torqueService?.isConnectedToECU == false)
    }

    @Suppress("UNUSED_PARAMETER")
    fun onDestroy(context: Context) {
        // Clear ownership before cleanup so repeated destruction is harmless.
        val receiverOwner = simulationReceiverContext
        val serviceOwner = bindingContext
        simulationReceiverContext = null
        bindingContext = null
        hasBound = false
        torqueService = null
        torqueConnection.forceReconnect = null
        if (receiverOwner != null) {
            try {
                receiverOwner.unregisterReceiver(simulationReceiver)
            } catch (e: IllegalArgumentException) {
                DiagnosticLog.event("TORQUE_RECEIVER_CLEANUP", e.javaClass.simpleName)
                Timber.w(e, "Simulation receiver already unregistered")
            }
        }
        if (serviceOwner != null) {
            try {
                serviceOwner.unbindService(torqueConnection)
            } catch (e: IllegalArgumentException) {
                DiagnosticLog.event("TORQUE_BINDING_CLEANUP", e.javaClass.simpleName)
                Timber.w(e, "Torque service already unbound")
            }
        }
    }

    fun requestQuit(context: Context) {
        context.sendBroadcast(Intent("org.prowl.torque.REQUEST_TORQUE_QUIT"))
        Timber.i("Torque stop")
    }
    fun startTorque(context: Context): Boolean {
        appContext = context.applicationContext
        if (simulationReceiverContext == null) {
            val filter = android.content.IntentFilter(ACTION_SIMULATION_SETTING_CHANGED)
            ContextCompat.registerReceiver(
                context,
                simulationReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            simulationReceiverContext = context
        }
        val intent = Intent()
        intent.setClassName("org.prowl.torque", "org.prowl.torque.remote.TorqueService")
        connectStartedMs = android.os.SystemClock.elapsedRealtime()
        if (!quietReconnectLogs) DiagnosticLog.event("TORQUE_CONNECT_ATTEMPT", "elapsedMs=0")
        // Keep the original binding owner until its matching unbind.
        if (hasBound) return true
        hasBound = context.bindService(intent, torqueConnection, Activity.BIND_AUTO_CREATE)
        bindingContext = if (hasBound) context else null
        if (!hasBound && !quietReconnectLogs) DiagnosticLog.event("TORQUE_CONNECT_FAILURE", "elapsedMs=${elapsedMs()} reason=bind_failed")
        torqueConnection.forceReconnect = {
            onDestroy(context)
            startTorque(context)
        }
        Timber.i(
            if (hasBound) "Connected to torque service!" else "Unable to connect to Torque plugin service"
        )
        return hasBound
    }

}