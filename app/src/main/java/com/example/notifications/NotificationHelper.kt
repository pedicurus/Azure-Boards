package com.example.notifications

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
import com.example.MainActivity
import com.example.R
import com.example.data.model.AppNotificationItem
import com.example.data.model.NotificationCategory

object NotificationHelper {

    const val CHANNEL_ASSIGNED = "azure_boards_assigned_channel"
    const val CHANNEL_MENTIONED = "azure_boards_mentions_channel"
    const val CHANNEL_ACCOUNT = "azure_boards_account_channel"

    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    const val EXTRA_NOTIFICATION_ORG = "extra_notification_org"
    const val EXTRA_NOTIFICATION_WORK_ITEM_ID = "extra_notification_work_item_id"
    const val EXTRA_OPEN_NOTIFICATIONS_SCREEN = "extra_open_notifications_screen"

    fun ensureNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val assignedChannel = NotificationChannel(
                CHANNEL_ASSIGNED,
                context.getString(R.string.notif_channel_assigned_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_assigned_desc)
                enableVibration(true)
            }

            val mentionedChannel = NotificationChannel(
                CHANNEL_MENTIONED,
                context.getString(R.string.notif_channel_mentioned_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_mentioned_desc)
                enableVibration(true)
            }

            val accountChannel = NotificationChannel(
                CHANNEL_ACCOUNT,
                context.getString(R.string.notif_channel_account_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_account_desc)
            }

            manager.createNotificationChannel(assignedChannel)
            manager.createNotificationChannel(mentionedChannel)
            manager.createNotificationChannel(accountChannel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun postSystemNotification(context: Context, item: AppNotificationItem) {
        ensureNotificationChannels(context)
        if (!hasNotificationPermission(context)) return

        val isMention = item.category == NotificationCategory.MENTIONED
        val channelId = if (isMention) CHANNEL_MENTIONED else CHANNEL_ASSIGNED

        val title = if (isMention) {
            val actor = item.actorName?.takeIf { it.isNotBlank() } ?: "Someone"
            "$actor mentioned you in #${item.workItemId}"
        } else {
            "Assigned to you: #${item.workItemId}"
        }

        val body = if (isMention) {
            val snippet = item.messageSnippet?.takeIf { it.isNotBlank() } ?: item.workItemTitle
            "${item.workItemTitle} — $snippet"
        } else {
            val metaParts = listOfNotNull(
                item.workItemType.takeIf { it.isNotBlank() },
                item.workItemState.takeIf { it.isNotBlank() },
                item.teamProject?.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            if (metaParts.isNotEmpty()) {
                "${item.workItemTitle}\n$metaParts"
            } else {
                item.workItemTitle
            }
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_ID, item.id)
            putExtra(EXTRA_NOTIFICATION_ORG, item.organization)
            putExtra(EXTRA_NOTIFICATION_WORK_ITEM_ID, item.workItemId)
        }

        val requestCode = item.id.hashCode()
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            tapIntent,
            pendingIntentFlags
        )

        val priority = if (isMention) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT

        // Shown instead of the real notification on a secure lock screen, so work item titles,
        // comment snippets and people's names are not readable without unlocking the device.
        val publicVersion = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_azure_devops)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(
                if (isMention) "You were mentioned in a work item" else "A work item was assigned to you"
            )
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_azure_devops)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // hasNotificationPermission() was checked above; the user can still revoke it at any
        // moment, so handle the SecurityException explicitly.
        try {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: skip this notification.
        }
    }

    /**
     * Tells the user that Azure DevOps rejected the saved token for [organization], so they can
     * enter a new one. Posted once when background sync first detects the rejection.
     */
    fun postReauthRequiredNotification(context: Context, organization: String) {
        ensureNotificationChannels(context)
        if (!hasNotificationPermission(context)) return

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val requestCode = "reauth:${organization.lowercase()}".hashCode()
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(context, requestCode, tapIntent, pendingIntentFlags)

        val body = context.getString(R.string.notif_reauth_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_ACCOUNT)
            .setSmallIcon(R.drawable.ic_azure_devops)
            .setContentTitle(context.getString(R.string.notif_reauth_title, organization))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // hasNotificationPermission() was checked above; the user can still revoke it at any
        // moment, so handle the SecurityException explicitly.
        try {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: skip this notification.
        }
    }

    /** Removes every notification this app has posted, e.g. when the user signs out. */
    fun cancelAllPosted(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
    }
}
