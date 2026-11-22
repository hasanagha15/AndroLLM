package io.androllm.core.jarvislink.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Wire protocol between Jarvis and the phone. UTF-8 JSON, MQTT v5, QoS 1.
 *
 * ```
 * task    Jarvis -> phone  {"v":1,"id","text","from","ts","ttl_s","sig"}
 * result  phone -> Jarvis  {"v":1,"id","status","text","ts","sig"}
 * status  phone -> all     retained {"v":1,"state":"online|offline","ts"}
 * ```
 * Every task is authenticated end-to-end: `sig` = hex HMAC-SHA256 with a
 * shared 32-byte key over [taskSigningInput]. The broker (and every other
 * broker client, e.g. Home Assistant under the shared user) can therefore
 * neither forge nor alter tasks. Results are signed the same way so Jarvis
 * can tell a real phone answer from a fake broker. `accepted` is an ack;
 * every other status is final except `duplicate` (informational).
 */
object JarvisTopics {
    const val TASK = "jarvis/phone/task"
    const val RESULT = "jarvis/phone/result"
    const val STATUS = "jarvis/phone/status"
}

/** A validated, authenticated task received from Jarvis. */
data class JarvisTask(
    val id: String,
    val text: String,
    val from: String,
    val sentAtMs: Long,
    val ttlSeconds: Long
) {
    fun expiresAtMs(): Long = sentAtMs + ttlSeconds * 1000 + JarvisProtocol.CLOCK_SKEW_MS
}

@Serializable
enum class ResultStatus {
    @SerialName("accepted") ACCEPTED,
    @SerialName("ok") OK,
    @SerialName("error") ERROR,
    @SerialName("denied") DENIED,
    @SerialName("expired") EXPIRED,
    @SerialName("duplicate") DUPLICATE,
    @SerialName("busy") BUSY,
    @SerialName("invalid") INVALID;

    /** Final = Jarvis may stop waiting. `accepted` and `duplicate` are not final. */
    val isFinal: Boolean get() = this != ACCEPTED && this != DUPLICATE

    val wire: String get() = name.lowercase()
}

@Serializable
data class ResultMessage(
    val v: Int = JarvisProtocol.VERSION,
    val id: String,
    val status: ResultStatus,
    val text: String = "",
    val ts: Long,
    val sig: String = ""
)

@Serializable
data class StatusMessage(
    val v: Int = JarvisProtocol.VERSION,
    val state: String,
    val ts: Long
)

/** Outcome of parsing a raw task payload. */
sealed interface TaskParse {
    data class Valid(val task: JarvisTask) : TaskParse
    /**
     * Rejected payload. [id] is only set when the payload was AUTHENTIC
     * (good signature) — forged payloads are never answered.
     */
    data class Invalid(val reason: String, val id: String?) : TaskParse
    data class Expired(val id: String, val ageMs: Long) : TaskParse
}

object JarvisProtocol {
    const val VERSION = 1
    const val MAX_ID_LENGTH = 64
    const val MAX_TEXT_LENGTH = 4_000
    const val MAX_PAYLOAD_BYTES = 16_384
    const val MAX_RESULT_TEXT = 8_000
    const val DEFAULT_TTL_S = 600L
    /** Short ttl + the persisted dedupe window make replay of a captured task useless. */
    const val MAX_TTL_S = 3_600L
    /** Clock skew tolerated between Jarvis and the phone (both NTP-synced). */
    const val CLOCK_SKEW_MS = 120_000L
    const val KEY_BYTES = 32

    private val ID_RE = Regex("^[A-Za-z0-9._:-]{1,$MAX_ID_LENGTH}$")
    private val HEX_RE = Regex("^[0-9a-fA-F]{64}$")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun taskSigningInput(id: String, ts: Long, ttl: Long, from: String, text: String): ByteArray =
        "androllm-task-v1\n$id\n$ts\n$ttl\n$from\n$text".toByteArray(Charsets.UTF_8)

    fun resultSigningInput(id: String, status: String, ts: Long, text: String): ByteArray =
        "androllm-result-v1\n$id\n$status\n$ts\n$text".toByteArray(Charsets.UTF_8)

    fun hmacHex(key: ByteArray, data: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    /** Parses the 64-hex-char shared key; null when malformed. */
    fun parseKeyHex(hex: String): ByteArray? {
        val h = hex.trim()
        if (!HEX_RE.matches(h)) return null
        return ByteArray(KEY_BYTES) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /**
     * Parses, authenticates and validates a task payload. Never throws.
     * Order: size → JSON → id/ts/text/sig present → signature (constant-time)
     * → version/limits → time window. Without a [key] nothing is accepted.
     */
    fun parseTask(payload: ByteArray, nowMs: Long, key: ByteArray?): TaskParse {
        if (key == null || key.size != KEY_BYTES) return TaskParse.Invalid("no task signing key configured", null)
        if (payload.size > MAX_PAYLOAD_BYTES) return TaskParse.Invalid("payload too large", null)
        val obj: JsonObject = try {
            json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            return TaskParse.Invalid("not a JSON object", null)
        }
        fun str(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun num(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        val id = str("id")?.takeIf { ID_RE.matches(it) } ?: return TaskParse.Invalid("missing or malformed id", null)
        val ts = num("ts") ?: return TaskParse.Invalid("missing ts", null)
        val text = str("text") ?: return TaskParse.Invalid("missing text", null)
        val from = str("from") ?: "jarvis"
        val ttlRaw = num("ttl_s") ?: DEFAULT_TTL_S
        val sig = str("sig")?.lowercase() ?: return TaskParse.Invalid("unsigned task", null)

        val expected = hmacHex(key, taskSigningInput(id, ts, ttlRaw, from, text))
        if (!MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray())) {
            return TaskParse.Invalid("bad signature", null)
        }
        // ── authentic from here on: rejections are answered by id ──
        val version = num("v") ?: VERSION.toLong()
        if (version != VERSION.toLong()) return TaskParse.Invalid("unsupported protocol version $version", id)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TaskParse.Invalid("missing text", id)
        if (trimmed.length > MAX_TEXT_LENGTH) return TaskParse.Invalid("text longer than $MAX_TEXT_LENGTH chars", id)
        if (ttlRaw < 1 || ttlRaw > MAX_TTL_S) return TaskParse.Invalid("ttl_s must be 1..$MAX_TTL_S", id)
        if (ts - nowMs > CLOCK_SKEW_MS) return TaskParse.Invalid("ts is in the future", id)
        val task = JarvisTask(id = id, text = trimmed, from = from.take(32), sentAtMs = ts, ttlSeconds = ttlRaw)
        if (nowMs > task.expiresAtMs()) return TaskParse.Expired(id, nowMs - ts)
        return TaskParse.Valid(task)
    }

    fun encodeResult(id: String, status: ResultStatus, text: String, nowMs: Long, key: ByteArray?): ByteArray {
        val clipped = if (text.length > MAX_RESULT_TEXT) text.take(MAX_RESULT_TEXT) + "\n[…truncated]" else text
        val sig = if (key != null) hmacHex(key, resultSigningInput(id, status.wire, nowMs, clipped)) else ""
        return json.encodeToString(
            ResultMessage.serializer(),
            ResultMessage(id = id, status = status, text = clipped, ts = nowMs, sig = sig)
        ).toByteArray(Charsets.UTF_8)
    }

    fun decodeResult(payload: ByteArray): ResultMessage =
        json.decodeFromString(ResultMessage.serializer(), payload.toString(Charsets.UTF_8))

    fun verifyResult(msg: ResultMessage, key: ByteArray): Boolean =
        MessageDigest.isEqual(
            hmacHex(key, resultSigningInput(msg.id, msg.status.wire, msg.ts, msg.text)).toByteArray(),
            msg.sig.lowercase().toByteArray()
        )

    fun encodeStatus(online: Boolean, nowMs: Long): ByteArray =
        json.encodeToString(StatusMessage.serializer(), StatusMessage(state = if (online) "online" else "offline", ts = nowMs))
            .toByteArray(Charsets.UTF_8)
}
