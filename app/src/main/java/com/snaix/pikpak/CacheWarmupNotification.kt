package com.snaix.pikpak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

internal object CacheWarmupNotification {
    const val ID = 7401
    private const val CHANNEL = "cache_warmup"

    fun build(context: Context, state: CacheWarmupState): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelName = appString(R.string.model_notification_cache_channel)
        val existing = manager.getNotificationChannel(CHANNEL)
        if (existing == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, channelName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            })
        } else if (existing.name.toString() != channelName) {
            existing.name = channelName
            manager.createNotificationChannel(existing)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(context, ID,
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val stop = PendingIntent.getService(context, ID, PreviewWarmupService.cancelIntent(context), flags)
        val total = state.total?.takeIf { it > 0 }
        val done = if (total == null) 0 else state.completed.coerceIn(0, total)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cache_notification)
            .setContentTitle(appString(R.string.model_notification_caching))
            .setContentText(state.summary.ifBlank { appString(R.string.model_cache_preparing) })
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, appString(R.string.model_cache_stop), stop).build())
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.ProgressStyle()
            if (total == null) style.setProgressIndeterminate(true)
            else style.setProgressSegments(listOf(Notification.ProgressStyle.Segment(total))).setProgress(done)
            builder.setStyle(style)
                .setShortCriticalText(if (total == null) appString(R.string.model_cache_short) else "${done * 100L / total}%")
            // The promotion hint also works on Android 16 before the 36.1 convenience setter.
            builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        } else {
            builder.setProgress(total ?: 0, done, total == null)
        }
        return builder.build()
    }
}
