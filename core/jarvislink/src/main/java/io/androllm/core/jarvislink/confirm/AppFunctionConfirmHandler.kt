package io.androllm.core.jarvislink.confirm

import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.remote.RemoteDataResult
import io.androllm.core.tools.remote.RemoteDataTaskHandler
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Branch V: owner tap-confirmation for an OUTWARD Android AppFunction (send / call / delete / update …) that
 * Jarvis wants to run through the Acer adb bridge (the app itself cannot call AppFunctions: the permission is
 * privileged). Data task `appfunction.confirm`, body:
 *
 * ```
 * {"canon": "{\"fn\":…,\"params\":{…},\"pkg\":…}",   // the EXACT canonical JSON the bridge recomputes
 *  "nonce": "16..64 [A-Za-z0-9_-]", "exp": epoch-ms (<= 10 min ahead)}
 * ```
 * Gate V B3: the card shows EVERY parameter field, nested fields flattened one per line, recipients / targets
 * first, control and bidi characters escaped. If the full text does not fit, the request is REFUSED ("too long
 * to show") — never truncated. The card is remote-origin and tap-only (a spoken "yes" never approves); no answer
 * within the manager timeout = deny.
 *
 * Gate V B1: result text `{"approved","digest":sha256(canon),"nonce","exp","sig"}`; sig = base64 DER
 * ECDSA-P256/SHA-256 over `afc2|digest|nonce|exp|1`, made by a non-exportable Android Keystore key
 * ([KeystoreConfirmSigner]) only after the tap. The bridge verifies it with the public key it pulled over adb.
 */
@Singleton
class AppFunctionConfirmHandler @Inject constructor(
    private val confirmations: ToolConfirmationManager,
    private val signer: ConfirmSigner
) : RemoteDataTaskHandler {

    override val types: Set<String> = setOf(TYPE)

    override suspend fun handle(type: String, body: JsonObject?): RemoteDataResult {
        val req = parse(body, System.currentTimeMillis()) ?: return RemoteDataResult(false, "invalid appfunction.confirm body")
        val summary = cardText(req) ?: return RemoteDataResult(false, "too long to show")
        val card = PendingToolConfirmation(
            id = CARD_ID_PREFIX + req.nonce,
            toolName = TOOL_PREFIX + req.fn.substringAfterLast('#').substringAfterLast('_'),
            toolDisplayName = "AppFunction · " + escape(req.pkg.substringAfterLast('.')),
            actionSummary = summary,
            speakableQuestion = "",
            remoteOrigin = "Jarvis",
            tapOnly = true
        )
        val approved = confirmations.awaitDecision(card)
        return RemoteDataResult(true, resultJson(signer, req, approved))
    }

    data class Request(val canon: String, val pkg: String, val fn: String, val params: JsonObject,
                       val nonce: String, val exp: Long)

    companion object {
        const val TYPE = "appfunction.confirm"
        const val CARD_ID_PREFIX = "afc_"
        const val TOOL_PREFIX = "appfunction_"

        /** True for cards raised by this handler (approval only on the full in-app card). */
        fun isAppFunctionCard(c: PendingToolConfirmation): Boolean =
            c.id.startsWith(CARD_ID_PREFIX) || c.toolName.startsWith(TOOL_PREFIX)

        /** SHA-256 of the public key, hex — shown in Settings so the owner can compare it with the Acer. */
        fun fingerprint(spki: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(spki).toHex()
        const val MAX_AGE_MS = 10 * 60 * 1000L
        private const val MAX_CANON = 16 * 1024
        const val MAX_LINES = 60
        const val MAX_VALUE_CHARS = 600
        const val MAX_CARD_CHARS = 3000
        private val NONCE = Regex("^[A-Za-z0-9_-]{16,64}$")
        private val json = Json { ignoreUnknownKeys = false }

        /** Fields naming who / where the action goes to — shown first. */
        private val TARGET = Regex(
            "(recipient|attendee|participant|invitee|target|destination|phone|number|email|address|person|contact|" +
                "device|url|uri|link|payee)", RegexOption.IGNORE_CASE
        )

        /** Strict parse; null on anything unexpected (expired, oversized, malformed, extra keys in canon). */
        fun parse(body: JsonObject?, nowMs: Long): Request? {
            if (body == null) return null
            val canon = (body["canon"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            val nonce = (body["nonce"] as? JsonPrimitive)?.contentOrNull ?: return null
            val exp = (body["exp"] as? JsonPrimitive)?.longOrNull ?: return null
            if (canon.length > MAX_CANON || !NONCE.matches(nonce)) return null
            if (exp <= nowMs || exp > nowMs + MAX_AGE_MS) return null
            val obj = runCatching { json.parseToJsonElement(canon).jsonObject }.getOrNull() ?: return null
            if (obj.keys != setOf("fn", "params", "pkg")) return null
            val pkg = (obj["pkg"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val fn = (obj["fn"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val params = obj["params"] as? JsonObject ?: return null
            if (pkg.isBlank() || fn.isBlank()) return null
            return Request(canon, pkg, fn, params, nonce, exp)
        }

        /** Escapes C0/C1 controls, line/paragraph separators, bidi overrides/isolates and zero-width marks. */
        fun escape(s: String): String = buildString {
            for (c in s) {
                val code = c.code
                val bad = code < 0x20 || code in 0x7F..0x9F || code in 0x200B..0x200F || code in 0x2028..0x202E ||
                    code in 0x2066..0x2069 || code == 0x061C || code == 0xFEFF
                if (bad) append("\\u%04x".format(code)) else append(c)
            }
        }

        private fun flatten(prefix: String, e: JsonElement, out: MutableList<Pair<String, String>>) {
            when (e) {
                is JsonObject -> if (e.isEmpty()) {
                    out += prefix to "{}"
                } else {
                    e.forEach { (k, v) -> flatten(if (prefix.isEmpty()) k else "$prefix.$k", v, out) }
                }
                is JsonArray -> if (e.isEmpty()) {
                    out += prefix to "[]"
                } else {
                    e.forEachIndexed { i, v -> flatten("$prefix[$i]", v, out) }
                }
                is JsonNull -> out += prefix to "null"
                is JsonPrimitive -> out += prefix to e.toString() // strings keep their quotes
            }
        }

        /** Full card text, or null when it cannot be shown completely (then the request is refused). */
        fun cardText(req: Request): String? {
            val fields = mutableListOf<Pair<String, String>>()
            flatten("", req.params, fields)
            val (targets, rest) = fields.partition { TARGET.containsMatchIn(it.first) }
            val ordered = targets + rest
            if (ordered.size > MAX_LINES) return null
            val sb = StringBuilder()
            sb.append(escape(req.pkg)).append('\n')
            sb.append(escape(req.fn.substringAfterLast('#'))).append('\n')
            for ((k, v) in ordered) {
                val ev = escape(v)
                if (ev.length > MAX_VALUE_CHARS) return null
                sb.append(escape(k)).append(" = ").append(ev).append('\n')
            }
            return if (sb.length > MAX_CARD_CHARS) null else sb.toString()
        }

        fun digest(canon: String): String =
            MessageDigest.getInstance("SHA-256").digest(canon.toByteArray(Charsets.UTF_8)).toHex()

        fun confirmMessage(digest: String, nonce: String, exp: Long): ByteArray =
            "afc2|$digest|$nonce|$exp|1".toByteArray(Charsets.UTF_8)

        fun resultJson(signer: ConfirmSigner, req: Request, approved: Boolean): String {
            val d = digest(req.canon)
            val sig = if (approved) {
                Base64.getEncoder().encodeToString(signer.sign(confirmMessage(d, req.nonce, req.exp)))
            } else {
                ""
            }
            return "{\"approved\":$approved,\"digest\":\"$d\",\"nonce\":\"${req.nonce}\",\"exp\":${req.exp},\"sig\":\"$sig\"}"
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
