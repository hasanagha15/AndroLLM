package io.androllm.core.jarvislink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.androllm.core.jarvislink.confirm.RemoteConfirmationRouter
import io.androllm.core.jarvislink.events.JarvisEventOutbox
import io.androllm.core.jarvislink.mqtt.HiveMqJarvisLink
import io.androllm.core.jarvislink.mqtt.LinkConfig
import io.androllm.core.jarvislink.mqtt.LinkState
import io.androllm.core.jarvislink.mqtt.RouteDecision
import io.androllm.core.jarvislink.mqtt.TailscaleRoute
import io.androllm.core.jarvislink.processing.JarvisTaskRunner
import io.androllm.core.jarvislink.processing.RemoteTaskProcessor
import io.androllm.core.jarvislink.processing.RemoteTaskTracker
import io.androllm.core.jarvislink.processing.TaskDeduplicator
import io.androllm.core.jarvislink.settings.JarvisLinkSettingsStore
import io.androllm.core.tools.remote.RemoteDataTaskHandler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Foreground service holding the MQTT link to Jarvis. Runs only while the
 * link is enabled in Settings (off by default). Type `remoteMessaging`.
 */
@AndroidEntryPoint
class JarvisLinkService : Service() {

    @Inject lateinit var settingsStore: JarvisLinkSettingsStore
    @Inject lateinit var runner: JarvisTaskRunner
    @Inject lateinit var tracker: RemoteTaskTracker
    @Inject lateinit var router: RemoteConfirmationRouter
    @Inject lateinit var status: JarvisLinkStatus
    // Branch N: typed data tasks (playbooks.sync / playbooks.relearn).
    @Inject lateinit var dataHandlers: Set<@JvmSuppressWildcards RemoteDataTaskHandler>
    // Branch R1: phone → Jarvis events (apps.changed), flushed while connected.
    @Inject lateinit var eventOutbox: JarvisEventOutbox

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var link: HiveMqJarvisLink? = null
    private var processor: RemoteTaskProcessor? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // A sticky restart from the background may not be allowed to go
        // foreground (Android 12+): stop cleanly instead of crash-looping.
        inForeground = try {
            startInForeground(buildNotification("Connecting…"))
            true
        } catch (e: Exception) {
            Timber.w(e, "JarvisLink: startForeground not allowed now — stopping")
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!inForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        val settings = settingsStore.current()
        if (!settings.enabled || !settings.isComplete) {
            Timber.i("JarvisLink: disabled or incomplete settings — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        val password = settingsStore.password()
        if (password == null || settingsStore.signingKey() == null) {
            // e.g. restored from a backup on a new device: Keystore key is gone.
            status.set(LinkState.ConfigError("Stored password or signing key unreadable — enter them again"))
            stopSelf()
            return START_NOT_STICKY
        }
        if (link != null) return START_STICKY

        lateinit var newProcessor: RemoteTaskProcessor
        val newLink = HiveMqJarvisLink(
            scope = scope,
            onTask = { payload -> scope.launch { newProcessor.onPayload(payload) } },
            onConnected = {
                scope.launch {
                    newProcessor.flushPending()
                    eventOutbox.flushNow()
                }
            },
            routeCheck = { host -> checkRoute(applicationContext, host) }
        )
        newProcessor = RemoteTaskProcessor(
            runner = runner,
            publisher = { bytes -> newLink.publishResult(bytes) },
            deduplicator = TaskDeduplicator(store = settingsStore),
            tracker = tracker,
            signingKey = { settingsStore.signingKey() },
            dataHandlers = dataHandlers
        )
        processor = newProcessor
        link = newLink
        eventOutbox.attach { bytes -> newLink.publishEvent(bytes) }
        newProcessor.start(scope)
        router.start(scope)
        scope.launch {
            newLink.state.collect { st ->
                status.set(st)
                updateNotification(describe(st))
            }
        }
        newLink.start(
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
        eventOutbox.detach()
        processor?.stop()
        val l = link
        link = null
        processor = null
        // Publish retained "offline" and disconnect off the main thread (bounded).
        if (l != null) {
            CoroutineScope(Dispatchers.IO).launch { withTimeoutOrNull(7_000) { l.disconnect() } }
        }
        if (status.state.value !is LinkState.ConfigError) status.set(LinkState.Off)
        scope.cancel()
        super.onDestroy()
    }

    private fun describe(st: LinkState): String = when (st) {
        LinkState.Off -> "Off"
        LinkState.Connecting -> "Connecting…"
        LinkState.Connected -> "Connected — waiting for Jarvis tasks"
        is LinkState.Retrying -> "Reconnecting in ${st.inMs / 1000}s"
        is LinkState.AuthFailed -> "Broker rejected the credentials"
        is LinkState.WaitingForRoute -> "Waiting for Tailscale"
        is LinkState.Refused -> "Not connecting: ${st.reason}"
        is LinkState.ConfigError -> st.reason
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
                    description = "Connection to Jarvis (MQTT over Tailscale) while remote tasks are enabled"
                }
            )
        }
    }

    companion object {
        const val SERVICE_CHANNEL = "jarvis_link"
        const val NOTIFICATION_ID = 0x4A4C01

        /**
         * Only over Tailscale: host must be a Tailscale IP or *.ts.net name,
         * a VPN network must be up, and the name must resolve (through that
         * VPN network) to a Tailscale address. Connects to the resolved IP.
         */
        @Suppress("DEPRECATION")
        suspend fun checkRoute(context: Context, host: String): RouteDecision = withContext(Dispatchers.IO) {
            if (TailscaleRoute.classify(host) == TailscaleRoute.HostKind.NOT_TAILSCALE) {
                return@withContext RouteDecision.Refuse("host is not a Tailscale address (100.64.0.0/10) or *.ts.net name")
            }
            val cm = context.getSystemService(ConnectivityManager::class.java)
                ?: return@withContext RouteDecision.Wait("no connectivity service")
            // A VPN network whose own interface address is a Tailscale address
            // (any other VPN does not count).
            val vpn = cm.allNetworks.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                    cm.getLinkProperties(net)?.linkAddresses?.any { TailscaleRoute.isTailscaleAddress(it.address) } == true
            } ?: return@withContext RouteDecision.Wait("Tailscale VPN is not connected")
            val addrs = try {
                vpn.getAllByName(host).toList()
            } catch (e: Exception) {
                return@withContext RouteDecision.Wait("cannot resolve the broker via the VPN")
            }
            val ts = addrs.firstOrNull(TailscaleRoute::isTailscaleAddress)
                ?: return@withContext RouteDecision.Refuse("broker name does not resolve to a Tailscale address")
            RouteDecision.Go(ts.hostAddress ?: host)
        }
    }
}
