package io.androllm.core.jarvislink.confirm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.jarvislink.protocol.JarvisTask
import io.androllm.core.jarvislink.service.JarvisConfirmReceiver
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Heads-up notification with Approve / Deny for a confirmation raised by a
 * remote (Jarvis) task. Both actions require the device to be unlocked
 * (`setAuthenticationRequired`), so nobody can approve from the lock screen.
 * Tapping the body opens the app, where the regular chat card is shown too.
 */
@Singleton
class NotificationConfirmationNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) : ConfirmationNotifier {

    override fun show(confirmation: PendingToolConfirmation, task: JarvisTask?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Without the permission only the in-app card can decide; the
            // manager's timeout denies if nobody answers. Never auto-approve.
            Timber.w("JarvisLink: POST_NOTIFICATIONS missing — confirmation only via in-app card")
            return
        }
        ensureChannel(context)
        val approve = actionIntent(JarvisConfirmReceiver.ACTION_APPROVE, confirmation.id, 1)
        val deny = actionIntent(JarvisConfirmReceiver.ACTION_DENY, confirmation.id, 2)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 3, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        // The ACTION first (complete arguments — ToolExecutor refuses anything too
        // long to show), then a short excerpt of the task for context.
        val body = buildString {
            append(confirmation.actionSummary)
            append("\n\n").append(confirmation.remoteOrigin ?: "Jarvis")
            if (task != null) {
                append(" — задача: «").append(task.text.take(200))
                if (task.text.length > 200) append("…")
                append("»")
            }
            append("\nПолный текст — в карточке в приложении.")
        }
        val notification = NotificationCompat.Builder(context, CONFIRM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Подтвердите: ${confirmation.toolDisplayName}")
            .setContentText(confirmation.actionSummary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setAutoCancel(false)
            .apply { if (open != null) setContentIntent(open) }
            .addAction(
                NotificationCompat.Action.Builder(0, "Deny", deny).setAuthenticationRequired(true).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(0, "Approve", approve).setAuthenticationRequired(true).build()
            )
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(CONFIRM_NOTIFICATION_ID, notification) }
            .onFailure { Timber.w(it, "JarvisLink: could not post confirmation notification") }
    }

    override fun cancel() {
        NotificationManagerCompat.from(context).cancel(CONFIRM_NOTIFICATION_ID)
    }

    private fun actionIntent(action: String, id: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, JarvisConfirmReceiver::class.java)
            .setAction(action)
            .setPackage(context.packageName)
            .putExtra(JarvisConfirmReceiver.EXTRA_CONFIRMATION_ID, id)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        const val CONFIRM_CHANNEL = "jarvis_link_confirm"
        const val CONFIRM_NOTIFICATION_ID = 0x4A4C02

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CONFIRM_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CONFIRM_CHANNEL, "Jarvis confirmations", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Approve or deny actions requested by Jarvis tasks"
                    }
                )
            }
        }
    }
}
