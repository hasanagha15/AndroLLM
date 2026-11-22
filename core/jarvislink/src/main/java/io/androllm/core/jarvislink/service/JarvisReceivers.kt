package io.androllm.core.jarvislink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.androllm.core.jarvislink.confirm.RemoteConfirmationRouter
import javax.inject.Inject
import timber.log.Timber

/**
 * Approve / Deny taps from the confirmation notification. Not exported: only
 * our own immutable PendingIntents can reach it, and the notification actions
 * require the device to be unlocked.
 */
@AndroidEntryPoint
class JarvisConfirmReceiver : BroadcastReceiver() {

    @Inject lateinit var router: RemoteConfirmationRouter

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_CONFIRMATION_ID) ?: return
        val handled = when (intent.action) {
            ACTION_APPROVE -> router.approve(id)
            ACTION_DENY -> router.deny(id)
            else -> false
        }
        if (!handled) Timber.i("JarvisLink: stale confirmation action ignored")
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
