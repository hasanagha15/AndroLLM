package io.androllm.core.jarvislink.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Wire protocol between Jarvis (pi-brain) and the phone. All payloads are
 * UTF-8 JSON, MQTT v5, QoS 1.
 *
 * ```
 * jarvis/phone/task    Jarvis -> phone  {"v":1,"id":"..","text":"..","from":"jarvis","ts":<ms>,"ttl_s":600}
 * jarvis/phone/result  phone -> Jarvis  {"v":1,"id":"..","status":"accepted|ok|error|denied|expired|duplicate|busy|invalid","text":"..","ts":<ms>}
 * jarvis/phone/status  phone -> all     retained {"v":1,"state":"online|offline","ts":<ms>,"app":"androllm"}
 * ```
 * `accepted` is an acknowledgement; every other result status is final.
 */
object JarvisTopics {
    const val TASK = "jarvis/phone/task"
    const val RESULT = "jarvis/phone/result"
    const val STATUS = "jarvis/phone/status"
}

/** A validated task received from Jarvis. */
data class JarvisTask(
    val id: String,
    val text: String,
    val from: String,
    val sentAtMs: Long?,
    val ttlSeconds: Long
)

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

    val isFinal: Boolean get() = this != ACCEPTED
}

@Serializable
data class ResultMessage(
    val v: Int = JarvisProtocol.VERSION,
    val id: String,
    val status: ResultStatus,
    val text: String = "",
    val ts: Long
)

@Serializable
data class StatusMessage(
    val v: Int = JarvisProtocol.VERSION,
    val state: String,
    val ts: Long,
    val app: String = "androllm"
)

/** Outcome of parsing a raw task payload. */
sealed interface TaskParse {
    data class Valid(val task: JarvisTask) : TaskParse
    /** Rejected payload. [id] is set when the payload carried a usable id, so the rejection can be answered. */
    data class Invalid(val reason: String, val id: String?) : TaskParse
    data class Expired(val id: String, val ageMs: Long) : TaskParse
}

object JarvisProtocol {
    const val VERSION = 1
    const val MAX_ID_LENGTH = 64
    const val MAX_TEXT_LENGTH = 4_000
    const val MAX_PAYLOAD_BYTES = 16_384
    const val MAX_RESULT_TEXT = 60_000
    const val DEFAULT_TTL_S = 600L
    const val MAX_TTL_S = 86_400L
    /** Clock skew tolerated between pi-brain and the phone (both NTP-synced). */
    const val CLOCK_SKEW_MS = 120_000L

    private val ID_RE = Regex("^[A-Za-z0-9._:-]{1,$MAX_ID_LENGTH}$")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Parses and validates a task payload. Never throws. A task whose
     * `ts + ttl_s` lies in the past (beyond [CLOCK_SKEW_MS]) is [TaskParse.Expired]
     * — a queued QoS 1 task must never execute long after Jarvis gave up on it.
     */
    fun parseTask(payload: ByteArray, nowMs: Long): TaskParse {
        if (payload.size > MAX_PAYLOAD_BYTES) return TaskParse.Invalid("payload too large", null)
        val obj: JsonObject = try {
            json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            return TaskParse.Invalid("not a JSON object", null)
        }
        val rawId = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        val id = rawId?.takeIf { ID_RE.matches(it) }
            ?: return TaskParse.Invalid("missing or malformed id", null)

        val version = (obj["v"] as? JsonPrimitive)?.longOrNull ?: VERSION.toLong()
        if (version != VERSION.toLong()) return TaskParse.Invalid("unsupported protocol version $version", id)

        val text = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        if (text.isNullOrEmpty()) return TaskParse.Invalid("missing text", id)
        if (text.length > MAX_TEXT_LENGTH) return TaskParse.Invalid("text longer than $MAX_TEXT_LENGTH chars", id)

        val from = (obj["from"] as? JsonPrimitive)?.contentOrNull?.take(32) ?: "jarvis"
        val ts = (obj["ts"] as? JsonPrimitive)?.longOrNull
        val ttl = ((obj["ttl_s"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_TTL_S).coerceIn(1L, MAX_TTL_S)

        if (ts != null) {
            val age = nowMs - ts
            if (age > ttl * 1000 + CLOCK_SKEW_MS) return TaskParse.Expired(id, age)
        }
        return TaskParse.Valid(JarvisTask(id = id, text = text, from = from, sentAtMs = ts, ttlSeconds = ttl))
    }

    fun encodeResult(id: String, status: ResultStatus, text: String, nowMs: Long): ByteArray {
        val clipped = if (text.length > MAX_RESULT_TEXT) text.take(MAX_RESULT_TEXT) + "\n[…truncated]" else text
        return json.encodeToString(ResultMessage.serializer(), ResultMessage(id = id, status = status, text = clipped, ts = nowMs))
            .toByteArray(Charsets.UTF_8)
    }

    fun decodeResult(payload: ByteArray): ResultMessage =
        json.decodeFromString(ResultMessage.serializer(), payload.toString(Charsets.UTF_8))

    fun encodeStatus(online: Boolean, nowMs: Long): ByteArray =
        json.encodeToString(StatusMessage.serializer(), StatusMessage(state = if (online) "online" else "offline", ts = nowMs))
            .toByteArray(Charsets.UTF_8)
}
