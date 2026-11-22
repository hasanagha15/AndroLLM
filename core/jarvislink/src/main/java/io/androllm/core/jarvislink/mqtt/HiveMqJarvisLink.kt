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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** Connection state shown in Settings. */
sealed interface LinkState {
    data object Off : LinkState
    data object Connecting : LinkState
    data object Connected : LinkState
    data class Retrying(val reason: String, val inMs: Long) : LinkState
    data class AuthFailed(val reason: String) : LinkState
}

/** Broker endpoint and credentials for one connection attempt (password never logged). */
class LinkConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: ByteArray,
    val clientId: String
)

/**
 * MQTT 5 link to the Jarvis broker built on the HiveMQ client.
 *
 * - Persistent session (`cleanStart=false`, 24 h expiry) + QoS 1 subscription
 *   to [JarvisTopics.TASK]: tasks sent while the phone was offline arrive on
 *   reconnect (and are then dropped by ttl if stale — see [JarvisProtocol.parseTask]).
 * - Retained `online` on [JarvisTopics.STATUS] after every connect; the
 *   broker publishes the retained `offline` Last-Will if the phone vanishes.
 * - Reconnect with exponential [Backoff] + jitter; auth failures retry at the
 *   slow cap instead of hammering the broker.
 */
class HiveMqJarvisLink(
    private val onTask: (ByteArray) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: Backoff = Backoff()
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Off)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    @Volatile private var client: Mqtt5AsyncClient? = null
    @Volatile private var stopping = false

    fun connect(config: LinkConfig) {
        stopping = false
        backoff.reset()
        val c = MqttClient.builder()
            .useMqttVersion5()
            .identifier(config.clientId)
            .serverHost(config.host)
            .serverPort(config.port)
            .addConnectedListener {
                backoff.reset()
                _state.value = LinkState.Connected
                Timber.i("JarvisLink: connected to %s:%d", config.host, config.port)
                onConnected()
            }
            .addDisconnectedListener { ctx ->
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
                val delay = if (authFailed) backoff.authFailureDelayMs() else backoff.nextDelayMs()
                val reason = cause.message ?: cause::class.java.simpleName
                _state.value = if (authFailed) LinkState.AuthFailed(reason) else LinkState.Retrying(reason, delay)
                Timber.w("JarvisLink: disconnected (%s), reconnect in %d ms", reason, delay)
                ctx.reconnector.reconnect(true).delay(delay, TimeUnit.MILLISECONDS)
            }
            .buildAsync()
        client = c

        // One global callback for the client's lifetime; the subscription is
        // (re)issued on every connect so it survives an expired session.
        c.publishes(MqttGlobalPublishFilter.SUBSCRIBED) { publish ->
            if (publish.topic.toString() == JarvisTopics.TASK) {
                onTask(publish.payloadAsBytes)
            }
        }

        _state.value = LinkState.Connecting
        c.connectWith()
            .cleanStart(false)
            .sessionExpiryInterval(SESSION_EXPIRY_S)
            .keepAlive(KEEP_ALIVE_S)
            .simpleAuth()
            .username(config.username)
            .password(config.password)
            .applySimpleAuth()
            .willPublish()
            .topic(JarvisTopics.STATUS)
            .payload(JarvisProtocol.encodeStatus(online = false, nowMs = clock()))
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .applyWillPublish()
            .send()
            .whenComplete { _, error ->
                // A failed first CONNECT also lands in the disconnected
                // listener, which schedules the backoff reconnect.
                if (error != null) Timber.w("JarvisLink: initial connect failed: %s", error.message)
            }
        // NOTE: config.password is not zeroed here — the client keeps the
        // CONNECT (credentials included) for automatic reconnects.
    }

    private fun onConnected() {
        val c = client ?: return
        c.subscribeWith()
            .topicFilter(JarvisTopics.TASK)
            .qos(MqttQos.AT_LEAST_ONCE)
            .send()
            .whenComplete { _, e -> if (e != null) Timber.w("JarvisLink: subscribe failed: %s", e.message) }
        c.publishWith()
            .topic(JarvisTopics.STATUS)
            .payload(JarvisProtocol.encodeStatus(online = true, nowMs = clock()))
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .send()
    }

    /** Publishes a result (QoS 1). Suspends until the broker's PUBACK or [PUBLISH_TIMEOUT_MS]. */
    suspend fun publishResult(payload: ByteArray) {
        val c = client ?: error("not connected")
        withTimeout(PUBLISH_TIMEOUT_MS) {
            val result = c.publishWith()
                .topic(JarvisTopics.RESULT)
                .payload(payload)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
                .await()
            result.error.ifPresent { throw it }
        }
    }

    /** Graceful stop: retained `offline`, then DISCONNECT (no Last-Will on a clean disconnect). */
    suspend fun disconnect() {
        stopping = true
        val c = client ?: return
        client = null
        runCatching {
            withTimeout(3_000) {
                c.publishWith()
                    .topic(JarvisTopics.STATUS)
                    .payload(JarvisProtocol.encodeStatus(online = false, nowMs = clock()))
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .retain(true)
                    .send()
                    .await()
            }
        }
        runCatching { withTimeout(3_000) { c.disconnect().await() } }
        _state.value = LinkState.Off
    }

    companion object {
        const val SESSION_EXPIRY_S = 86_400L
        const val KEEP_ALIVE_S = 30
        const val PUBLISH_TIMEOUT_MS = 30_000L
    }
}
