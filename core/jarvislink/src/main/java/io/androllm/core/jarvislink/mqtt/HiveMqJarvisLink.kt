package io.androllm.core.jarvislink.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import io.androllm.core.jarvislink.protocol.JarvisProtocol
import io.androllm.core.jarvislink.protocol.JarvisTopics
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Connection state shown in Settings. */
sealed interface LinkState {
    data object Off : LinkState
    data object Connecting : LinkState
    data object Connected : LinkState
    data class Retrying(val reason: String, val inMs: Long) : LinkState
    data class AuthFailed(val reason: String) : LinkState
    data class WaitingForRoute(val reason: String) : LinkState
    data class Refused(val reason: String) : LinkState
    data class ConfigError(val reason: String) : LinkState
}

/** Broker credentials for the link (password never logged). */
class LinkConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: ByteArray,
    val clientId: String
)

/**
 * MQTT 5 link to the Jarvis broker on the HiveMQ client.
 *
 * - Before EVERY connect attempt [routeCheck] must approve the route (Tailscale
 *   address + VPN up); otherwise no CONNECT (and no password) is sent.
 * - One fresh client per attempt; reconnects are scheduled here with
 *   exponential [Backoff] + jitter (slow cap on auth failures).
 * - Persistent session (`cleanStart=false`) + QoS 1 subscription on the task
 *   topic; retained tasks are ignored. Retained `online` after connect, retained
 *   `offline` Last-Will.
 */
class HiveMqJarvisLink(
    private val scope: CoroutineScope,
    private val onTask: (ByteArray) -> Unit,
    private val onConnected: () -> Unit,
    private val routeCheck: suspend (String) -> RouteDecision,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: Backoff = Backoff()
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Off)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    @Volatile private var client: Mqtt5AsyncClient? = null
    @Volatile private var stopping = false
    @Volatile private var config: LinkConfig? = null
    private val generation = AtomicInteger(0)

    fun start(cfg: LinkConfig) {
        stopping = false
        config = cfg
        backoff.reset()
        val gen = generation.incrementAndGet()
        scope.launch { attempt(gen) }
    }

    private suspend fun attempt(gen: Int) {
        val cfg = config ?: return
        var decision = routeCheck(cfg.host)
        while (decision is RouteDecision.Wait) {
            if (stopping || gen != generation.get()) return
            _state.value = LinkState.WaitingForRoute(decision.reason)
            delay(ROUTE_RECHECK_MS)
            decision = routeCheck(cfg.host)
        }
        if (stopping || gen != generation.get()) return
        when (decision) {
            is RouteDecision.Refuse -> {
                _state.value = LinkState.Refused(decision.reason)
                Timber.w("JarvisLink: route refused (%s) — not connecting", decision.reason)
                return
            }
            is RouteDecision.Go -> connect(cfg, decision.host, gen)
            is RouteDecision.Wait -> Unit
        }
    }

    private fun connect(cfg: LinkConfig, host: String, gen: Int) {
        val c = MqttClient.builder()
            .useMqttVersion5()
            .identifier(cfg.clientId)
            .serverHost(host)
            .serverPort(cfg.port)
            .addConnectedListener {
                if (gen != generation.get()) return@addConnectedListener
                backoff.reset()
                _state.value = LinkState.Connected
                Timber.i("JarvisLink: connected")
                afterConnect()
                onConnected()
            }
            .addDisconnectedListener { ctx ->
                if (gen != generation.get()) return@addDisconnectedListener
                if (stopping || ctx.source == MqttDisconnectSource.USER) {
                    _state.value = LinkState.Off
                    return@addDisconnectedListener
                }
                val cause = ctx.cause
                val authFailed = cause is Mqtt5ConnAckException && cause.mqttMessage.reasonCode.let {
                    it == Mqtt5ConnAckReasonCode.NOT_AUTHORIZED ||
                        it == Mqtt5ConnAckReasonCode.BAD_USER_NAME_OR_PASSWORD ||
                        it == Mqtt5ConnAckReasonCode.BAD_AUTHENTICATION_METHOD
                }
                val delayMs = if (authFailed) backoff.authFailureDelayMs() else backoff.nextDelayMs()
                val reason = cause.javaClass.simpleName
                _state.value = if (authFailed) LinkState.AuthFailed(reason) else LinkState.Retrying(reason, delayMs)
                Timber.w("JarvisLink: disconnected (%s), next attempt in %d ms", reason, delayMs)
                // No HiveMQ auto-reconnect: the next attempt re-checks the route first.
                val next = generation.incrementAndGet()
                scope.launch {
                    delay(delayMs)
                    attempt(next)
                }
            }
            .buildAsync()
        client = c

        c.publishes(MqttGlobalPublishFilter.SUBSCRIBED) { publish ->
            // Retained messages on the task topic are never executed.
            if (!publish.isRetain && publish.topic.toString() == JarvisTopics.TASK) {
                onTask(publish.payloadAsBytes)
            }
        }

        _state.value = LinkState.Connecting
        c.connectWith()
            .cleanStart(false)
            .sessionExpiryInterval(SESSION_EXPIRY_S)
            .keepAlive(KEEP_ALIVE_S)
            .simpleAuth()
            .username(cfg.username)
            .password(cfg.password)
            .applySimpleAuth()
            .willPublish()
            .topic(JarvisTopics.STATUS)
            .payload(JarvisProtocol.encodeStatus(online = false, nowMs = clock()))
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .applyWillPublish()
            .send()
            .whenComplete { _, error ->
                if (error != null) Timber.w("JarvisLink: connect failed: %s", error.javaClass.simpleName)
            }
    }

    private fun afterConnect() {
        val c = client ?: return
        c.subscribeWith()
            .topicFilter(JarvisTopics.TASK)
            .qos(MqttQos.AT_LEAST_ONCE)
            .send()
            .whenComplete { _, e -> if (e != null) Timber.w("JarvisLink: subscribe failed: %s", e.javaClass.simpleName) }
        c.publishWith()
            .topic(JarvisTopics.STATUS)
            .payload(JarvisProtocol.encodeStatus(online = true, nowMs = clock()))
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .send()
    }

    /**
     * Publishes a result (QoS 1). Throws [IOException] on failure or after
     * [PUBLISH_TIMEOUT_MS] — never a CancellationException, so a slow network
     * cannot cancel the caller's worker loop.
     */
    suspend fun publishResult(payload: ByteArray) = publish(JarvisTopics.RESULT, payload)

    /** Branch R1: publishes an encrypted phone event on `jarvis/phone/event` (QoS 1). Same failure contract. */
    suspend fun publishEvent(payload: ByteArray) = publish(JarvisTopics.EVENT, payload)

    private suspend fun publish(topic: String, payload: ByteArray) {
        val c = client ?: throw IOException("not connected")
        if (_state.value != LinkState.Connected) throw IOException("not connected")
        val result = withTimeoutOrNull(PUBLISH_TIMEOUT_MS) {
            runCatching {
                c.publishWith()
                    .topic(topic)
                    .payload(payload)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .send()
                    .await()
            }
        } ?: throw IOException("publish timed out")
        val published = result.getOrElse { throw IOException("publish failed: ${it.javaClass.simpleName}") }
        published.error.ifPresent { throw IOException("publish rejected: ${it.javaClass.simpleName}") }
    }

    /** Graceful stop: retained `offline`, then DISCONNECT (no Last-Will on a clean disconnect). */
    suspend fun disconnect() {
        stopping = true
        generation.incrementAndGet()
        val c = client ?: run { _state.value = LinkState.Off; return }
        client = null
        withTimeoutOrNull(3_000) {
            runCatching {
                c.publishWith()
                    .topic(JarvisTopics.STATUS)
                    .payload(JarvisProtocol.encodeStatus(online = false, nowMs = clock()))
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .retain(true)
                    .send()
                    .await()
            }
        }
        withTimeoutOrNull(3_000) { runCatching { c.disconnect().await() } }
        _state.value = LinkState.Off
    }

    companion object {
        const val SESSION_EXPIRY_S = 86_400L
        const val KEEP_ALIVE_S = 60
        const val PUBLISH_TIMEOUT_MS = 30_000L
        const val ROUTE_RECHECK_MS = 15_000L
    }
}
