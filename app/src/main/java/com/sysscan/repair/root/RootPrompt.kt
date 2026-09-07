package com.sysscan.repair.root

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sysscan.repair.MainActivity
import com.sysscan.repair.R

object RootPrompt {

    const val CHANNEL_ID = "sysscan_root"
    const val NOTIFICATION_ID = 2201

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.root_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.root_channel_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, openManager: Boolean) {
        ensureChannel(context)
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_REQUEST_ROOT, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            1,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(context.getString(R.string.root_notify_title))
            .setContentText(context.getString(R.string.root_notify_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.root_notify_big))
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        if (canNotify(context)) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
        if (openManager) RootChecker.openManager(context)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    const val EXTRA_REQUEST_ROOT = "request_root"
}
