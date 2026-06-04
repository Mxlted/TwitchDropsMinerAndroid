package com.nathan.twitchdropsminer.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nathan.twitchdropsminer.android.MainActivity
import com.nathan.twitchdropsminer.android.R
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot

const val MinerNotificationId = 3107
const val MinerChannelId = "local_miner_runtime"

class MinerNotifications(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            MinerChannelId,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun monitoring(snapshot: RuntimeSnapshot): Notification {
        ensureChannel()
        val title = when (snapshot.phase) {
            RuntimePhase.Error -> "Local miner needs attention"
            RuntimePhase.Watching -> "Mining Twitch Drops locally"
            RuntimePhase.Claiming -> "Claiming a completed Drop"
            RuntimePhase.LoadingInventory,
            RuntimePhase.Fetching -> "Refreshing Drops inventory"
            RuntimePhase.FindingChannel -> "Finding eligible channels"
            RuntimePhase.SelectingCampaign -> "Selecting campaign"
            RuntimePhase.Authenticating -> "Waiting for Twitch login"
            RuntimePhase.Idle -> "Local miner is idle"
            RuntimePhase.Connecting -> "Local miner connecting"
            RuntimePhase.Stopped -> "Local miner stopped"
        }
        val channel = snapshot.watchingChannel?.name
        val drop = snapshot.activeDrop?.let {
            if (snapshot.phase == RuntimePhase.Claiming) {
                "Claiming ${it.name}"
            } else {
                "${it.name} ${it.currentMinutes}/${it.requiredMinutes}m"
            }
        }
        val text = snapshot.error ?: listOfNotNull(snapshot.currentTask, channel, drop)
            .joinToString(" - ")

        return NotificationCompat.Builder(context, MinerChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${snapshot.progressSummary}\n$text"))
            .setContentIntent(openAppIntent())
            .addAction(
                R.drawable.ic_notification,
                "Stop",
                stopIntent(),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun initial(): Notification =
        NotificationCompat.Builder(context, MinerChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Local miner starting")
            .setContentText("Preparing Android runtime")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun update(snapshot: RuntimeSnapshot) {
        notificationManager.notify(MinerNotificationId, monitoring(snapshot))
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopIntent(): PendingIntent =
        PendingIntent.getService(
            context,
            1,
            MinerForegroundService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
