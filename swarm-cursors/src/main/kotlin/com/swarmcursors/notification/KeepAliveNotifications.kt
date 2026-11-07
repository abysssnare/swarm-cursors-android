package com.swarmcursors.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.swarmcursors.R
import com.swarmcursors.core.log.SwarmLog

/**
 * Helper for integrators that want to run the accessibility service as a
 * foreground service (spec §2.3). The library never calls `startForeground`
 * itself — it only provides a ready-to-use notification.
 */
internal object KeepAliveNotifications {

    private const val CHANNEL_ID = "swarm_cursors_keep_alive"

    fun create(context: Context): Notification {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.keep_alive_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
        return Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_swarm_cursor)
            .setContentTitle(appContext.getString(R.string.keep_alive_title))
            .setContentText(appContext.getString(R.string.keep_alive_text))
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
            .also { SwarmLog.d("Keep-alive notification created") }
    }
}
