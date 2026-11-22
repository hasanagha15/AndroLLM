package io.androllm.core.jarvislink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.androllm.core.jarvislink.confirm.RemoteConfirmationRouter
import io.androllm.core.jarvislink.mqtt.HiveMqJarvisLink
import io.androllm.core.jarvislink.mqtt.LinkConfig
import io.androllm.core.jarvislink.mqtt.LinkState
import io.androllm.core.jarvislink.processing.JarvisTaskRunner
import io.androllm.core.jarvislink.processing.RemoteTaskProcessor
import io.androllm.core.jarvislink.processing.RemoteTaskTracker
import io.androllm.core.jarvislink.processing.TaskDeduplicator
import io.androllm.core.jarvislink.settings.JarvisLinkSettingsStore
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Foreground service holding the MQTT link to Jarvis. Runs only while the
 * link is enabled in Settings (off by default). Type `remoteMessaging`: it
 * relays text tasks/results between Jarvis and the on-device agent.
 */
@AndroidEntryPoint
class JarvisLinkService : Service() {

    @Inject lateinit var settingsStore: JarvisLinkSettingsStore
    @Inject lateinit var runner: JarvisTaskRunner
    @Inject lateinit var tracker: RemoteTaskTracker
    @Inject lateinit var router: RemoteConfirmationRouter
    @Inject lateinit var status: JarvisLinkStatus

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var link: HiveMqJarvisLink? = null
    private var processor: RemoteTaskProcessor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startInForeground(buildNotification("Connecting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = settingsStore.current()
        val password = settingsStore.password()
        if (!settings.enabled || !settings.isComplete || password == null) {
            Timber.i("JarvisLink: disabled or incomplete settings — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        if (link != null) return START_STICKY

        val newLink = HiveMqJarvisLink(onTask = { payload -> scope.launch { processor?.onPayload(payload) } })
        val newProcessor = RemoteTaskProcessor(
            runner = runner,
            publisher = { bytes -> newLink.publishResult(bytes) },
            deduplicator = TaskDeduplicator(store = settingsStore),
            tracker = tracker
        )
        processor = newProcessor
        link = newLink
        newProcessor.start(scope)
        router.start(scope)
        scope.launch {
            newLink.state.collect { st ->
                status.set(st)
                updateNotification(describe(st))
            }
        }
        newLink.connect(
            LinkConfig(
                host = settings.host,
                port = settings.port,
                username = settings.username,
                password = password,
                clientId = settingsStore.clientId()
            )
        )
        return START_STICKY
    }

    override fun onDestroy() {
        router.stop()
        processor?.stop()
        val l = link
        link = null
        processor = null
        // Publish retained "offline" and disconnect cleanly (bounded wait).
        if (l != null) runBlocking { withTimeoutOrNull(4_000) { l.disconnect() } }
        status.set(LinkState.Off)
        scope.cancel()
        super.onDestroy()
    }

    private fun describe(st: LinkState): String = when (st) {
        LinkState.Off -> "Off"
        LinkState.Connecting -> "Connecting…"
        LinkState.Connected -> "Connected — waiting for Jarvis tasks"
        is LinkState.Retrying -> "Reconnecting in ${st.inMs / 1000}s"
        is LinkState.AuthFailed -> "Broker rejected the credentials"
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Jarvis link")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (open != null) setContentIntent(open) }
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(SERVICE_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(SERVICE_CHANNEL, "Jarvis link", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Connection to Jarvis (MQTT) while remote tasks are enabled"
                }
            )
        }
    }

    companion object {
        const val SERVICE_CHANNEL = "jarvis_link"
        const val NOTIFICATION_ID = 0x4A4C01
    }
}
