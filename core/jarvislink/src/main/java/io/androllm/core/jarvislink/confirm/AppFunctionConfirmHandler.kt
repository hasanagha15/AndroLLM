package io.androllm.core.jarvislink.confirm

import io.androllm.core.jarvislink.protocol.JarvisCrypto
import io.androllm.core.jarvislink.settings.JarvisLinkSettingsStore
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.remote.RemoteDataResult
import io.androllm.core.tools.remote.RemoteDataTaskHandler
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
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
 * The card shows package, function and the parameters parsed from `canon` itself — what the owner sees is what
 * is hashed. It is ALWAYS tap-only (remote origin; a spoken "yes" never approves) and times out to deny.
 *
 * Result text: `{"approved":bool,"digest":sha256(canon),"nonce","exp","sig"}`, sig = HMAC-SHA256(confirmKey,
 * "afc1|digest|nonce|exp|1") only when approved; confirmKey = HKDF(task.key, "androllm-jarvislink",
 * "appfunction-confirm-v1"). The bridge holds only that derived key, checks digest == its own canonical JSON of
 * the request, the expiry and single use of the nonce.
 */
@Singleton
class AppFunctionConfirmHandler @Inject constructor(
    private val confirmations: ToolConfirmationManager,
    private val settings: JarvisLinkSettingsStore
) : RemoteDataTaskHandler {

    override val types: Set<String> = setOf(TYPE)

    override suspend fun handle(type: String, body: JsonObject?): RemoteDataResult {
        val master = settings.signingKey() ?: return RemoteDataResult(false, "no link key")
        val req = parse(body, System.currentTimeMillis()) ?: return RemoteDataResult(false, "invalid appfunction.confirm body")
        val card = PendingToolConfirmation(
            id = "afc_" + req.nonce,
            toolName = "appfunction_" + req.fn.substringAfterLast('#').substringAfterLast('_'),
            toolDisplayName = "AppFunction · " + req.pkg.substringAfterLast('.'),
            actionSummary = req.summary(),
            speakableQuestion = "",
            remoteOrigin = "Jarvis",
            tapOnly = true
        )
        val approved = confirmations.awaitDecision(card)
        return RemoteDataResult(true, resultJson(confirmKey(master), req, approved))
    }

    data class Request(val canon: String, val pkg: String, val fn: String, val params: JsonObject,
                       val nonce: String, val exp: Long) {
        fun summary(): String = buildString {
            append(pkg).append('\n').append(fn.substringAfterLast('#')).append('\n')
            params.forEach { (k, v) -> append(k).append(" = ").append(v.toString().take(MAX_VALUE_CHARS)).append('\n') }
        }.take(MAX_SUMMARY_CHARS)
    }

    companion object {
        const val TYPE = "appfunction.confirm"
        const val MAX_AGE_MS = 10 * 60 * 1000L
        private const val MAX_CANON = 16 * 1024
        private const val MAX_VALUE_CHARS = 300
        private const val MAX_SUMMARY_CHARS = 1500
        private val NONCE = Regex("^[A-Za-z0-9_-]{16,64}$")
        private val json = Json { ignoreUnknownKeys = false }

        fun confirmKey(master: ByteArray): ByteArray = JarvisCrypto.hkdf(master, "appfunction-confirm-v1")

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

        fun digest(canon: String): String =
            MessageDigest.getInstance("SHA-256").digest(canon.toByteArray(Charsets.UTF_8)).toHex()

        fun resultJson(key: ByteArray, req: Request, approved: Boolean): String {
            val d = digest(req.canon)
            val sig = if (approved) {
                val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
                mac.doFinal("afc1|$d|${req.nonce}|${req.exp}|1".toByteArray(Charsets.UTF_8)).toHex()
            } else {
                ""
            }
            return "{\"approved\":$approved,\"digest\":\"$d\",\"nonce\":\"${req.nonce}\",\"exp\":${req.exp},\"sig\":\"$sig\"}"
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
