package com.greenart7c3.morganite.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.greenart7c3.morganite.MainActivity
import com.greenart7c3.morganite.R

class HttpServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotification(): Notification {
        val notificationManager = NotificationManagerCompat.from(this)
        val channelId = "HttpServerServiceChannel"
        val groupId = "HttpServerServiceGroup"
        val group = NotificationChannelGroupCompat.Builder(groupId)
            .setName("Http Server")
            .build()
        notificationManager.createNotificationChannelGroup(group)

        val channel = NotificationChannelCompat.Builder(channelId, NotificationManager.IMPORTANCE_LOW)
            .setName("Http Server")
            .setGroup(groupId)
            .setSound(null, null)
            .build()
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notificationBuilder = NotificationCompat.Builder(this, "HttpServerServiceChannel")
            .setContentTitle("Service")
            .setContentText("Server is running in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setGroup(groupId)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        return notificationBuilder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        return START_STICKY
    }
}
