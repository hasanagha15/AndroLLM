package io.androllm.feature.voice.service

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
import io.androllm.core.navigation.Routes
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.voice.lang.VoiceLanguage
import timber.log.Timber

/**
 * Heads-up notification for a TAP-ONLY card raised in a spoken turn (the
 * voice responder never answers those). Mirrors the Jarvis-link notification
 * (high priority, private on the lock screen) but has NO Approve/Deny action:
 * tapping it opens the app on the conversation, where the regular chat card
 * is the only decision surface. Remote cards keep their own notification from
 * core/jarvislink.
 */
object VoiceCardNotifier {

    const val CHANNEL_ID = "voice_tap_confirm"
    const val NOTIFICATION_ID = 0x56430A

    fun show(
        context: Context,
        card: PendingToolConfirmation,
        language: VoiceLanguage,
        conversationId: String?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Voice: POST_NOTIFICATIONS missing — tap-only card only in the app")
            return
        }
        ensureChannel(context)
        val route = if (conversationId.isNullOrBlank()) Routes.CHAT else Routes.chatDetail(conversationId)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            it.putExtra(Routes.EXTRA_NAV_ROUTE, route)
            PendingIntent.getActivity(context, 7, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val body = card.actionSummary + "\n\n" + VoicePhrases.notificationHint(language)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(VoicePhrases.notificationTitle(language, card.toolDisplayName))
            .setContentText(card.actionSummary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setAutoCancel(true)
            .apply { if (open != null) setContentIntent(open) }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onFailure { Timber.w(it, "Voice: could not post tap-only card notification") }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Voice confirmations", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Actions requested by voice that must be confirmed in the app"
                }
            )
        }
    }
}
