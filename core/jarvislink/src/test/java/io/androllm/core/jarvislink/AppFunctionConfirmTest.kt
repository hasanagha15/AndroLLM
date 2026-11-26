package io.androllm.core.jarvislink

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvislink.confirm.AppFunctionConfirmHandler
import io.androllm.core.jarvislink.confirm.ConfirmSigner
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

/** Branch V: `appfunction.confirm` — Keystore-style ECDSA approvals, full card, refusal instead of truncation. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppFunctionConfirmTest {

    /** JCA stand-in for the Android Keystore signer (same algorithm: EC P-256, SHA256withECDSA, DER). */
    private class JcaSigner : ConfirmSigner {
        private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override fun publicKeySpki(): ByteArray = kp.public.encoded
        override fun sign(message: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(message); sign() }
    }

    private val canon = "{\"fn\":\"x#sendMessage\",\"params\":{\"a\":1},\"pkg\":\"com.samsung.android.messaging\"}"
    private val exp = 1_900_000_000_000L
    private val now = exp - 60_000

    private fun body(c: String = canon, nonce: String = "abcdefghijklmnop", e: Long = exp) =
        buildJsonObject { put("canon", c); put("nonce", nonce); put("exp", e) }

    private fun verify(spki: ByteArray, msg: ByteArray, sigB64: String): Boolean {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        return Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(msg); verify(Base64.getDecoder().decode(sigB64)) }
    }

    @Test
    fun `message format and DER signature match the python bridge`() {
        // signed by afcore.py (cryptography, ECDSA P-256 SHA-256) over afc2|digest|nonce|exp|1
        val spki = Base64.getDecoder().decode(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE8wsQJmJvtTzN3Cy1TuNq7o6TzOFATqKuaQq8oxmmZwaJ+Ymau/MQTnr2EdlHYFarEN5AEqsRL1uCqtQh37KHUg==")
        val d = AppFunctionConfirmHandler.digest(canon)
        assertThat(d).isEqualTo("1ce8083f3cf28918dc36cb03bbb050257944b78036bfc404cf6c487b33cd1cf0")
        val pySig = "MEUCIE7HET/nvvP62yLO8ad1kDpXrO7hInJ2IjdwxO9Wzfy4AiEAto3nStEgxrzTbqtN/8TFoBpV9ksxxDc4cnd5qgAGBpo="
        assertThat(verify(spki, AppFunctionConfirmHandler.confirmMessage(d, "abcdefghijklmnop", exp), pySig)).isTrue()
    }

    @Test
    fun `approval is signed by the device key, denial carries no signature`() {
        val signer = JcaSigner()
        val req = AppFunctionConfirmHandler.parse(body(), now)!!
        val ok = Json.parseToJsonElement(AppFunctionConfirmHandler.resultJson(signer, req, true)).jsonObject
        val d = ok["digest"]!!.jsonPrimitive.content
        assertThat(verify(signer.publicKeySpki(), AppFunctionConfirmHandler.confirmMessage(d, req.nonce, req.exp),
            ok["sig"]!!.jsonPrimitive.content)).isTrue()
        val no = Json.parseToJsonElement(AppFunctionConfirmHandler.resultJson(signer, req, false)).jsonObject
        assertThat(no["approved"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(no["sig"]!!.jsonPrimitive.content).isEmpty()
    }

    @Test
    fun `strict parsing`() {
        assertThat(AppFunctionConfirmHandler.parse(null, now)).isNull()
        assertThat(AppFunctionConfirmHandler.parse(body(e = now - 1), now)).isNull()
        assertThat(AppFunctionConfirmHandler.parse(body(e = now + 3_600_000), now)).isNull()
        assertThat(AppFunctionConfirmHandler.parse(body(nonce = "short"), now)).isNull()
        assertThat(AppFunctionConfirmHandler.parse(body(c = "{\"pkg\":\"a\",\"fn\":\"b\"}"), now)).isNull()
        assertThat(AppFunctionConfirmHandler.parse(body(c = "{\"pkg\":\"a\",\"fn\":\"b\",\"params\":{},\"x\":1}"), now)).isNull()
        assertThat(AppFunctionConfirmHandler.parse(body(c = "not json"), now)).isNull()
    }

    private fun sendCanon(text: String) =
        "{\"fn\":\"x#sendMessage\",\"params\":{\"sendMessageParams\":{\"body\":\"$text\"," +
            "\"messageAttachments\":[],\"recipientsIds\":[\"42\",\"77\"]}},\"pkg\":\"com.samsung.android.messaging\"}"

    @Test
    fun `card shows every nested field, recipients first, after a long body`() {
        val req = AppFunctionConfirmHandler.parse(body(c = sendCanon("x".repeat(320))), now)!!
        val card = AppFunctionConfirmHandler.cardText(req)!!
        val lines = card.lines()
        assertThat(lines[2]).isEqualTo("sendMessageParams.recipientsIds[0] = \"42\"")
        assertThat(lines[3]).isEqualTo("sendMessageParams.recipientsIds[1] = \"77\"")
        assertThat(card).contains("sendMessageParams.body = \"" + "x".repeat(320) + "\"")
        assertThat(card).contains("sendMessageParams.messageAttachments = []")
    }

    @Test
    fun `too long is refused, never truncated`() = runTest {
        val manager = ToolConfirmationManager()
        val handler = AppFunctionConfirmHandler(manager, JcaSigner())
        val long = buildJsonObject {
            put("canon", sendCanon("y".repeat(2000))); put("nonce", "abcdefghijklmnop")
            put("exp", System.currentTimeMillis() + 120_000)
        }
        val r = handler.handle(AppFunctionConfirmHandler.TYPE, long)
        assertThat(r.ok).isFalse()
        assertThat(r.text).isEqualTo("too long to show")
        assertThat(manager.pending.value).isNull()
    }

    @Test
    fun `bidi and control characters are escaped`() {
        val req = AppFunctionConfirmHandler.parse(body(c = sendCanon("pay \\u202Eevil\\u2066 \\n end")), now)!!
        val card = AppFunctionConfirmHandler.cardText(req)!!
        assertThat(card).doesNotContain("‮")
        assertThat(card).doesNotContain("⁦")
        assertThat(card).contains("\\u202e")
        assertThat(card).contains("\\u2066")
        assertThat(card.lines().none { it.trim() == "end\"" }).isTrue()   // embedded newline stays on its line
    }

    @Test
    fun `card is remote and tap-only, the owner's tap decides`() = runTest {
        val manager = ToolConfirmationManager()
        val signer = JcaSigner()
        val handler = AppFunctionConfirmHandler(manager, signer)
        val fresh = buildJsonObject {
            put("canon", canon); put("nonce", "abcdefghijklmnop"); put("exp", System.currentTimeMillis() + 120_000)
        }
        val r = async { handler.handle(AppFunctionConfirmHandler.TYPE, fresh) }
        runCurrent()
        val card = manager.pending.value!!
        assertThat(card.remoteOrigin).isNotNull()
        assertThat(card.tapOnly).isTrue()
        assertThat(ToolConfirmationManager.isTapOnly(card)).isTrue()
        assertThat(card.actionSummary).contains("com.samsung.android.messaging")
        manager.confirm(card.id)
        val res = r.await()
        assertThat(res.ok).isTrue()
        assertThat(res.text).contains("\"approved\":true")
    }
}
