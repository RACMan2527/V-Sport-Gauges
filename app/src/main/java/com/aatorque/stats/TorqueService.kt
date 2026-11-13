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

class TorqueService {
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
    private var simulationReceiverRegistered = false
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
                DiagnosticLog.event("TORQUE_CONNECTED", "elapsedMs=${elapsedMs()} component=$arg0")
                applySimulationSetting()
                torqueService = svc
                conLock.withLock {
                    for (funt in onConnect) {
                        funt(svc)
                    }
                    onConnect.clear()
                }
            } catch (e: DeadObjectException) {
                DiagnosticLog.event("TORQUE_DISCONNECTED", "elapsedMs=${elapsedMs()} reason=dead_object")
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
            DiagnosticLog.event("TORQUE_DISCONNECTED", "elapsedMs=${elapsedMs()} component=$name")
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

    fun onDestroy(context: Context) {
        if (simulationReceiverRegistered) {
            context.unregisterReceiver(simulationReceiver)
            simulationReceiverRegistered = false
        }
        if (torqueService != null) {
            context.unbindService(torqueConnection)
            hasBound = false
            torqueService = null
        }
    }

    fun requestQuit(context: Context) {
        context.sendBroadcast(Intent("org.prowl.torque.REQUEST_TORQUE_QUIT"))
        Timber.i("Torque stop")
    }
    fun startTorque(context: Context): Boolean {
        appContext = context.applicationContext
        if (!simulationReceiverRegistered) {
            val filter = android.content.IntentFilter(ACTION_SIMULATION_SETTING_CHANGED)
            ContextCompat.registerReceiver(
                context,
                simulationReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            simulationReceiverRegistered = true
        }
        val intent = Intent()
        intent.setClassName("org.prowl.torque", "org.prowl.torque.remote.TorqueService")
        connectStartedMs = android.os.SystemClock.elapsedRealtime()
        DiagnosticLog.event("TORQUE_CONNECT_ATTEMPT", "elapsedMs=0")
        hasBound = context.bindService(intent, torqueConnection, Activity.BIND_AUTO_CREATE)
        if (!hasBound) DiagnosticLog.event("TORQUE_CONNECT_FAILURE", "elapsedMs=${elapsedMs()} reason=bind_failed")
        torqueConnection.forceReconnect = {
            onDestroy(context)
            context.bindService(intent, torqueConnection, Activity.BIND_AUTO_CREATE)
        }
        Timber.i(
            if (hasBound) "Connected to torque service!" else "Unable to connect to Torque plugin service"
        )
        return hasBound
    }

}