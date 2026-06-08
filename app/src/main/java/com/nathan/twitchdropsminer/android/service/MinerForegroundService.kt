package com.nathan.twitchdropsminer.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
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
            stopMinerAndService(startId, "Foreground local miner service stopped")
            return START_NOT_STICKY
        }

        // On a sticky/null-intent restart, refuse to mine invisibly: a foreground service
        // must show its ongoing notification. If the user disabled notifications, stop cleanly.
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            serviceScope.launch {
                graph.logRepository.load()
                graph.logRepository.append(
                    "WARN",
                    "Notifications are disabled; stopping foreground local miner so it does not run invisibly",
                )
            }
            stopMinerAndService(startId, "Foreground local miner service stopped (notifications disabled)")
            return START_NOT_STICKY
        }

        startForegroundCompat(notifications.initial())
        graph.localMinerRuntime.startMining()
        if (notificationJob == null) {
            notificationJob = serviceScope.launch {
                graph.logRepository.load()
                graph.logRepository.append("INFO", "Foreground local miner service started")
                graph.localMinerRuntime.snapshot.collectLatest { snapshot ->
                    notifications.update(snapshot)
                    // A non-recoverable terminal state (an expired/invalid Twitch token that the
                    // runtime cannot retry) must not leave the service alive forever with a stale
                    // ongoing notification. Tear it down instead.
                    if (snapshot.isNonRecoverableTerminal()) {
                        stopMinerAndService(
                            startId,
                            "Foreground local miner service stopped after Twitch session needs renewal",
                        )
                    }
                }
            }
        }
        return START_STICKY
    }

    /**
     * Cancels the runtime, removes the foreground notification, and stops the service.
     * Cancellation is awaited (stopMiningAndJoin) before the notification is removed so the
     * watch loop is not still running after the service goes away.
     */
    private fun stopMinerAndService(startId: Int, logMessage: String) {
        notificationJob?.cancel()
        notificationJob = null
        serviceScope.launch {
            graph.localMinerRuntime.stopMiningAndJoin()
            graph.logRepository.append("INFO", logMessage)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
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

/**
 * True when the runtime has stopped because the stored Twitch session expired or became invalid.
 * The runtime cancels its own mining job in this case and cannot recover without a new login, so
 * the foreground service should stop rather than idle forever showing a stale notification.
 */
internal fun RuntimeSnapshot.isNonRecoverableTerminal(): Boolean =
    phase == RuntimePhase.Authenticating && account.state == LoginState.Expired
