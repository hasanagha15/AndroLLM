package io.androllm.core.jarvislink.protocol

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Wire protocol v2 between Jarvis and the phone. MQTT v5, QoS 1.
 *
 * Task and result payloads are ENCRYPTED and AUTHENTICATED end-to-end with
 * AES-256-GCM. Keys are derived with HKDF-SHA256 from the shared 32-byte
 * `task.key` — one key per direction, so a result can never be replayed as a
 * task. The broker and every other broker client (e.g. Home Assistant under
 * the shared user) see only `{"v":2,"kind","n","c"}` and can neither read,
 * forge, alter nor reflect messages. The plaintext is a JSON object, so field
 * boundaries are unambiguous.
 *
 * ```
 * envelope   {"v":2,"kind":"task"|"result","n":b64(12-byte nonce),"c":b64(ciphertext||tag)}
 * task       {"id","text","from","ts","ttl_s"}        key = HKDF(k, "androllm-jarvislink", "task-v2"),   AAD "androllm/v2/task"
 * data task  {"id","type","body"?,"from","ts","ttl_s"}  (branch N: e.g. "playbooks.sync" / "playbooks.relearn";
 *            same keys and AAD, no agent turn; payload up to MAX_DATA_PAYLOAD_BYTES)
 * result     {"id","status","text","ts"}              key = HKDF(k, "androllm-jarvislink", "result-v2"), AAD "androllm/v2/result"
 * event      {"id","type","body","ts"}                key = HKDF(k, "androllm-jarvislink", "event-v2"),  AAD "androllm/v2/event"
 *            (branch R1: phone → Jarvis on `jarvis/phone/event`, envelope kind "event", e.g. "apps.changed";
 *            not an answer to any task, never parsed as a task or result)
 * status     retained plaintext {"v":2,"state":"online|offline","ts"}
 * ```
 * `accepted` is an ack; every other status is final except `duplicate`.
 */
object JarvisTopics {
    const val TASK = "jarvis/phone/task"
    const val RESULT = "jarvis/phone/result"
    const val STATUS = "jarvis/phone/status"
    /** Branch R1: phone-originated events (`apps.changed`), phone → Jarvis. */
    const val EVENT = "jarvis/phone/event"
}

/** A validated, authenticated task received from Jarvis. */
data class JarvisTask(
    val id: String,
    val text: String,
    val from: String,
    val sentAtMs: Long,
    val ttlSeconds: Long,
    /** Branch N: typed data task ("playbooks.sync"...) - handled without an agent turn. Null = normal text task. */
    val type: String? = null,
    /** Branch N: JSON body of a typed task. */
    val body: JsonObject? = null
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

    companion object {
        fun fromWire(s: String?): ResultStatus? = entries.firstOrNull { it.wire == s }
    }
}

/** Branch R1: decrypted event (tests / tooling; the Jarvis side decodes the same in Python). */
data class EventMessage(val id: String, val type: String, val body: JsonObject, val ts: Long)

/** Decrypted result (tests / tooling). */
data class ResultMessage(val id: String, val status: ResultStatus, val text: String, val ts: Long)

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
     * (decrypted with our key) — forged payloads are never answered.
     */
    data class Invalid(val reason: String, val id: String?) : TaskParse
    data class Expired(val id: String, val ageMs: Long) : TaskParse
}

/** HKDF-SHA256 + AES-256-GCM helpers (identical construction in jarvis-phone-task). */
object JarvisCrypto {
    const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val SALT = "androllm-jarvislink".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun hkdf(ikm: ByteArray, info: String, length: Int = 32): ByteArray {
        val prk = hmac(SALT, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info.toByteArray(Charsets.UTF_8) + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    fun seal(key: ByteArray, aad: String, plaintext: ByteArray, nonce: ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return nonce to c.doFinal(plaintext)
    }

    /** Returns the plaintext, or null when the tag does not verify. */
    fun open(key: ByteArray, aad: String, nonce: ByteArray, ciphertext: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        c.doFinal(ciphertext)
    } catch (e: Exception) {
        null
    }
}

object JarvisProtocol {
    const val VERSION = 2
    const val MAX_ID_LENGTH = 64
    const val MAX_TEXT_LENGTH = 4_000
    const val MAX_PAYLOAD_BYTES = 16_384
    /** Branch N: typed data tasks (playbook bundles) may be larger; text tasks keep [MAX_PAYLOAD_BYTES]. */
    const val MAX_DATA_PAYLOAD_BYTES = 393_216
    const val MAX_RESULT_TEXT = 8_000
    const val DEFAULT_TTL_S = 600L
    /** Short ttl + the persisted dedupe window make replay of a captured task useless. */
    const val MAX_TTL_S = 3_600L
    /** Clock skew tolerated between Jarvis and the phone (both NTP-synced). */
    const val CLOCK_SKEW_MS = 120_000L
    const val KEY_BYTES = 32
    const val AAD_TASK = "androllm/v2/task"
    const val AAD_RESULT = "androllm/v2/result"
    /** Branch R1: phone-originated events. */
    const val AAD_EVENT = "androllm/v2/event"
    const val MAX_EVENT_BYTES = 4_096

    private val ID_RE = Regex("^[A-Za-z0-9._:-]{1,$MAX_ID_LENGTH}$")
    private val FROM_RE = Regex("^[A-Za-z0-9._-]{1,32}$")
    private val TYPE_RE = Regex("^[a-z][a-z0-9._-]{0,39}$")
    private val HEX_RE = Regex("^[0-9a-fA-F]{64}$")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun taskKey(master: ByteArray) = JarvisCrypto.hkdf(master, "task-v2")
    fun resultKey(master: ByteArray) = JarvisCrypto.hkdf(master, "result-v2")
    fun eventKey(master: ByteArray) = JarvisCrypto.hkdf(master, "event-v2")

    /** Branch R1: event id / type rules (same alphabet as task ids and types). */
    fun isValidEventId(id: String) = ID_RE.matches(id)
    fun isValidEventType(type: String) = TYPE_RE.matches(type)

    /** Parses the 64-hex-char shared key; null when malformed. */
    fun parseKeyHex(hex: String): ByteArray? {
        val h = hex.trim()
        if (!HEX_RE.matches(h)) return null
        return ByteArray(KEY_BYTES) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Builds an encrypted task envelope (used by tests; the Jarvis side does the same in Python). */
    fun encodeTask(
        master: ByteArray, id: String, text: String, ts: Long, ttl: Long = DEFAULT_TTL_S, from: String = "jarvis",
        type: String? = null, body: JsonObject? = null
    ): ByteArray {
        val inner = buildJsonObject {
            put("id", id)
            if (type == null || text.isNotEmpty()) put("text", text)
            put("from", from); put("ts", ts); put("ttl_s", ttl)
            if (type != null) put("type", type)
            if (body != null) put("body", body)
        }.toString().toByteArray(Charsets.UTF_8)
        return envelope("task", JarvisCrypto.seal(taskKey(master), AAD_TASK, inner))
    }

    /**
     * Decrypts, authenticates and validates a task envelope. Never throws.
     * Without a [master] key nothing is accepted; anything that does not
     * decrypt (plaintext, forged, altered, wrong key, a result) is Invalid
     * with no id and is never answered.
     */
    fun parseTask(payload: ByteArray, nowMs: Long, master: ByteArray?): TaskParse {
        if (master == null || master.size != KEY_BYTES) return TaskParse.Invalid("no task key configured", null)
        if (payload.size > MAX_DATA_PAYLOAD_BYTES) return TaskParse.Invalid("payload too large", null)
        val inner = openEnvelope(payload, "task", taskKey(master), AAD_TASK)
            ?: return TaskParse.Invalid("not an authentic task envelope", null)
        val obj = runCatching { json.parseToJsonElement(inner.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
            ?: return TaskParse.Invalid("bad task body", null)
        fun str(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun num(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        val id = str("id")?.takeIf { ID_RE.matches(it) } ?: return TaskParse.Invalid("missing or malformed id", null)
        // ── authentic from here on: rejections are answered by id ──
        val ts = num("ts") ?: return TaskParse.Invalid("missing ts", id)
        // Branch N: typed data task.
        val typeEl = obj["type"]
        val type = (typeEl as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (typeEl != null && (type == null || !TYPE_RE.matches(type))) return TaskParse.Invalid("malformed type", id)
        if (type == null && payload.size > MAX_PAYLOAD_BYTES) return TaskParse.Invalid("payload too large", id)
        val bodyEl = obj["body"]
        val body = bodyEl as? JsonObject
        if (bodyEl != null && (type == null || body == null)) return TaskParse.Invalid("body only allowed as an object of a typed task", id)
        val text = (str("text")?.trim() ?: if (type != null) "" else null) ?: return TaskParse.Invalid("missing text", id)
        val from = str("from") ?: "jarvis"
        val ttl = num("ttl_s") ?: DEFAULT_TTL_S
        if (!FROM_RE.matches(from)) return TaskParse.Invalid("malformed from", id)
        if (text.isEmpty() && type == null) return TaskParse.Invalid("missing text", id)
        if (text.length > MAX_TEXT_LENGTH) return TaskParse.Invalid("text longer than $MAX_TEXT_LENGTH chars", id)
        if (ttl < 1 || ttl > MAX_TTL_S) return TaskParse.Invalid("ttl_s must be 1..$MAX_TTL_S", id)
        if (ts - nowMs > CLOCK_SKEW_MS) return TaskParse.Invalid("ts is in the future", id)
        val task = JarvisTask(id = id, text = text, from = from, sentAtMs = ts, ttlSeconds = ttl, type = type, body = body)
        if (nowMs > task.expiresAtMs()) return TaskParse.Expired(id, nowMs - ts)
        return TaskParse.Valid(task)
    }

    fun encodeResult(id: String, status: ResultStatus, text: String, nowMs: Long, master: ByteArray): ByteArray {
        val clipped = if (text.length > MAX_RESULT_TEXT) text.take(MAX_RESULT_TEXT) + "\n[…truncated]" else text
        val inner = buildJsonObject {
            put("id", id); put("status", status.wire); put("text", clipped); put("ts", nowMs)
        }.toString().toByteArray(Charsets.UTF_8)
        return envelope("result", JarvisCrypto.seal(resultKey(master), AAD_RESULT, inner))
    }

    /**
     * Branch R1: encrypts a phone-originated event (own key + AAD: an event can
     * never be replayed as a task or taken for a result). [body] carries ids /
     * package names / numbers only.
     */
    fun encodeEvent(id: String, type: String, body: JsonObject, ts: Long, master: ByteArray): ByteArray =
        encodeEventWithNonce(id, type, body, ts, master, nonce = null)

    /**
     * Gate R1 R-j: a fixed nonce exists for the cross-language test vector only
     * (a repeated nonce breaks GCM) — internal to this module, never public API.
     */
    internal fun encodeEventWithNonce(
        id: String, type: String, body: JsonObject, ts: Long, master: ByteArray, nonce: ByteArray?
    ): ByteArray {
        require(isValidEventId(id)) { "malformed event id" }
        require(isValidEventType(type)) { "malformed event type" }
        val inner = buildJsonObject {
            put("id", id); put("type", type); put("body", body); put("ts", ts)
        }.toString().toByteArray(Charsets.UTF_8)
        require(inner.size <= MAX_EVENT_BYTES) { "event too large" }
        val sealed = if (nonce != null) JarvisCrypto.seal(eventKey(master), AAD_EVENT, inner, nonce)
        else JarvisCrypto.seal(eventKey(master), AAD_EVENT, inner)
        return envelope("event", sealed)
    }

    /** Branch R1: decrypts an event envelope; null when not authentic or malformed. */
    fun decodeEvent(payload: ByteArray, master: ByteArray): EventMessage? {
        val inner = openEnvelope(payload, "event", eventKey(master), AAD_EVENT) ?: return null
        val obj = runCatching { json.parseToJsonElement(inner.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return null
        fun str(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val id = str("id")?.takeIf(::isValidEventId) ?: return null
        val type = str("type")?.takeIf(::isValidEventType) ?: return null
        val body = obj["body"] as? JsonObject ?: return null
        val ts = (obj["ts"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        return EventMessage(id, type, body, ts)
    }

    /** Decrypts a result envelope; null when not authentic. */
    fun decodeResult(payload: ByteArray, master: ByteArray): ResultMessage? {
        val inner = openEnvelope(payload, "result", resultKey(master), AAD_RESULT) ?: return null
        val obj = runCatching { json.parseToJsonElement(inner.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return null
        fun str(name: String) = (obj[name] as? JsonPrimitive)?.contentOrNull
        val status = ResultStatus.fromWire(str("status")) ?: return null
        return ResultMessage(str("id") ?: return null, status, str("text").orEmpty(), str("ts")?.toLongOrNull() ?: 0L)
    }

    fun encodeStatus(online: Boolean, nowMs: Long): ByteArray =
        json.encodeToString(StatusMessage.serializer(), StatusMessage(state = if (online) "online" else "offline", ts = nowMs))
            .toByteArray(Charsets.UTF_8)

    private fun envelope(kind: String, sealed: Pair<ByteArray, ByteArray>): ByteArray =
        buildJsonObject {
            put("v", VERSION)
            put("kind", kind)
            put("n", Base64.getEncoder().encodeToString(sealed.first))
            put("c", Base64.getEncoder().encodeToString(sealed.second))
        }.toString().toByteArray(Charsets.UTF_8)

    private fun openEnvelope(payload: ByteArray, kind: String, key: ByteArray, aad: String): ByteArray? {
        val obj: JsonObject = runCatching { json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
            ?: return null
        if ((obj["v"] as? JsonPrimitive)?.longOrNull != VERSION.toLong()) return null
        if ((obj["kind"] as? JsonPrimitive)?.contentOrNull != kind) return null
        val nonce = runCatching { Base64.getDecoder().decode((obj["n"] as JsonPrimitive).content) }.getOrNull() ?: return null
        val ct = runCatching { Base64.getDecoder().decode((obj["c"] as JsonPrimitive).content) }.getOrNull() ?: return null
        if (nonce.size != JarvisCrypto.NONCE_BYTES || ct.size < 16) return null
        return JarvisCrypto.open(key, aad, nonce, ct)
    }
}
