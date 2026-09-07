package com.sma.atsvslog.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.sma.atsvslog.MainActivity
import com.sma.atsvslog.database.entity.MasterConflictEntity

/**
 * Posts a single actionable notification for the oldest unresolved Model
 * ownership conflict. The conflict itself remains durable in Room; the
 * notification is only a prompt to resolve it.
 */
object MasterConflictNotificationManager {
    private const val CHANNEL_ID = "master_conflicts"
    private const val NOTIFICATION_ID = 4101

    const val EXTRA_OPEN_MASTER_CONFLICT =
        "com.sma.atsvslog.extra.OPEN_MASTER_CONFLICT"

    fun notify(context: Context, conflict: MasterConflictEntity): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        createChannel(notificationManager)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_MASTER_CONFLICT, true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(
            context,
            CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("ATSVSLog: sale needs attention")
            .setContentText(
                "Model ${conflict.model} has a Type/Brand conflict. Tap to review or correct."
            )
            .setStyle(
                android.app.Notification.BigTextStyle().bigText(
                    "Model ${conflict.model} is saved as " +
                        "${conflict.canonicalType} / ${conflict.canonicalBrand}, " +
                        "but this sale uses ${conflict.requestedType} / " +
                        "${conflict.requestedBrand}. Tap to review or correct before upload."
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        return true
    }

    fun cancel(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Catalogue conflicts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description =
                        "Alerts for Model Type/Brand conflicts that need resolution"
                }
            )
        }
    }
}
