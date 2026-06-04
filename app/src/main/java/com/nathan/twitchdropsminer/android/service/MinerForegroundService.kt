package com.nathan.twitchdropsminer.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.nathan.twitchdropsminer.android.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MinerForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var graph: AppGraph
    private lateinit var notifications: MinerNotifications
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.from(this)
        notifications = MinerNotifications(this)
        notifications.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionStop) {
            notificationJob?.cancel()
            notificationJob = null
            serviceScope.launch {
                graph.localMinerRuntime.stopMiningAndJoin()
                graph.logRepository.append("INFO", "Foreground local miner service stopped")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        startForegroundCompat(notifications.initial())
        if (notificationJob == null) {
            notificationJob = serviceScope.launch {
                graph.logRepository.load()
                graph.logRepository.append("INFO", "Foreground local miner service started")
                graph.localMinerRuntime.snapshot.collectLatest { snapshot ->
                    notifications.update(snapshot)
                }
            }
        }
        graph.localMinerRuntime.startMining()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notificationJob?.cancel()
        graph.localMinerRuntime.stopMining()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MinerNotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(MinerNotificationId, notification)
        }
    }

    companion object {
        private const val ActionStop = "com.nathan.twitchdropsminer.android.STOP_LOCAL_MINER"

        fun start(context: Context) {
            val intent = Intent(context, MinerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MinerForegroundService::class.java))
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, MinerForegroundService::class.java).setAction(ActionStop)
    }
}
