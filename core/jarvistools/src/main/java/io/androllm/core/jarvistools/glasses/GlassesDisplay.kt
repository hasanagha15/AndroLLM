package io.androllm.core.jarvistools.glasses

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Output to Even Realities G2 glasses.
 *
 * Research (22.11.2026): there is no official Android SDK for G2. Even's
 * official route is Even Hub (web apps running inside the Even app's WebView,
 * `@evenrealities/even_hub_sdk`) — not usable from a native app. The direct
 * BLE protocol is only community reverse-engineered (i-soxi/even-g2-protocol,
 * Commute773/g2-kit, gpsnmeajp/men-g2-ble-gateway): two arms, a multi-packet
 * auth handshake, CRC-16/CCITT framing, and it works only while the Even app
 * is NOT connected (the glasses accept one central). Taking the glasses away
 * from the Even app would break the owner's Even AI agents (tesla-bridge,
 * wa-bot, jarvis-glasses), so raw BLE is NOT implemented ([BleGlassesDisplay]
 * is a stub that explains why).
 *
 * What is real and safe today: the Even app mirrors phone notifications of
 * whitelisted apps onto the G2 display. [NotificationMirrorGlassesDisplay]
 * posts a short notification on a dedicated channel; the owner enables
 * AndroLLM in Even app → Notifications once.
 */
interface GlassesDisplay {
    /** Human-readable reason when the display path cannot work right now, else null. */
    fun unavailableReason(): String?

    /** Shows [title]/[text] on the glasses. Returns null on success or an error message. */
    fun show(title: String, text: String): String?
}

object GlassesText {
    const val MAX_TITLE = 40
    const val MAX_TEXT = 240

    /** G2 notification area is tiny: plain text, one paragraph, trimmed. */
    fun sanitize(raw: String, max: Int): String =
        raw.replace(Regex("[*_`#>\\[\\]]"), "").replace(Regex("\\s+"), " ").trim().take(max)
}

@Singleton
class NotificationMirrorGlassesDisplay @Inject constructor(
    @ApplicationContext private val context: Context
) : GlassesDisplay {

    private val ids = AtomicInteger(7300)

    override fun unavailableReason(): String? {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return "Notification permission is not granted to AndroLLM (needed to mirror text to the G2)."
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "Notifications are disabled for AndroLLM."
        return null
    }

    override fun show(title: String, text: String): String? {
        unavailableReason()?.let { return it }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Even G2 glasses", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Short texts the assistant shows on the Even G2 (mirrored by the Even app)" }
            )
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(GlassesText.sanitize(title, GlassesText.MAX_TITLE))
            .setContentText(GlassesText.sanitize(text, GlassesText.MAX_TEXT))
            .setStyle(NotificationCompat.BigTextStyle().bigText(GlassesText.sanitize(text, GlassesText.MAX_TEXT)))
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        return try {
            nm.notify(ids.incrementAndGet(), n)
            null
        } catch (e: SecurityException) {
            "Notification was blocked: ${e.message}"
        }
    }

    companion object {
        const val CHANNEL = "jarvis_g2_glasses"
    }
}

/**
 * Placeholder for a future direct-BLE path (see [GlassesDisplay] KDoc for why
 * it is not implemented). Always unavailable; never touches Bluetooth.
 */
class BleGlassesDisplay : GlassesDisplay {
    override fun unavailableReason(): String =
        "Direct BLE to Even G2 is not implemented: no official SDK, the reverse-engineered protocol needs the Even app disconnected."

    override fun show(title: String, text: String): String = unavailableReason()
}
