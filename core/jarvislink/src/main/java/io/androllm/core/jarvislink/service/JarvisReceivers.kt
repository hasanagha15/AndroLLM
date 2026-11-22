package io.androllm.core.jarvislink.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.startup.Initializer
import dagger.hilt.android.AndroidEntryPoint
import io.androllm.core.jarvislink.confirm.RemoteConfirmationRouter
import io.androllm.core.jarvislink.settings.JarvisLinkSettingsStore
import javax.inject.Inject
import timber.log.Timber

/**
 * Approve / Deny taps from the remote-confirmation notification. Not
 * exported: only our own immutable PendingIntents reach it. Approve is
 * ignored while the device is locked (the action also requires unlock on
 * Android 12+; this check covers API 28–30 where that flag is ignored).
 */
@AndroidEntryPoint
class JarvisConfirmReceiver : BroadcastReceiver() {

    @Inject lateinit var router: RemoteConfirmationRouter

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_CONFIRMATION_ID) ?: return
        val handled = when (intent.action) {
            ACTION_APPROVE -> {
                val locked = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true
                if (locked) {
                    Timber.w("JarvisLink: approve ignored — device is locked")
                    false
                } else {
                    router.approve(id)
                }
            }
            ACTION_DENY -> router.deny(id)
            else -> false
        }
        if (!handled) Timber.i("JarvisLink: confirmation action not applied")
    }

    companion object {
        const val ACTION_APPROVE = "io.androllm.core.jarvislink.APPROVE"
        const val ACTION_DENY = "io.androllm.core.jarvislink.DENY"
        const val EXTRA_CONFIRMATION_ID = "confirmation_id"
    }
}

/** Re-starts the link after reboot / app update when the user left it enabled. */
@AndroidEntryPoint
class JarvisBootReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: JarvisLinkController

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> controller.sync()
        }
    }
}

/**
 * Re-attaches the link when the app process starts (after a force-stop or a
 * crash the sticky service is gone). Runs from androidx.startup, before Hilt is
 * ready, so it only reads the plain enable flag and starts the service; the
 * service itself re-validates everything. A start that Android refuses from
 * the background is ignored.
 */
class JarvisLinkStartup : Initializer<Unit> {
    override fun create(context: Context) {
        val prefs: SharedPreferences =
            context.getSharedPreferences(JarvisLinkSettingsStore.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(JarvisLinkSettingsStore.K_ENABLED, false)) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, JarvisLinkService::class.java))
        }.onFailure { Timber.i("JarvisLink: start at process launch not allowed now (%s)", it.javaClass.simpleName) }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
