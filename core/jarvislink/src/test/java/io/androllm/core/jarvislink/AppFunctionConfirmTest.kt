package io.androllm.core.jarvislink

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvislink.confirm.AppFunctionConfirmHandler
import io.androllm.core.jarvislink.settings.JarvisLinkSettingsStore
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.mockk.every
import io.mockk.mockk
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

/** Branch V: `appfunction.confirm` — vectors computed by the Python side (afcore.py on pi-brain / Acer bridge). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppFunctionConfirmTest {
    private val master = ByteArray(32) { 0x11 }
    private val canon = "{\"fn\":\"x#sendMessage\",\"params\":{\"a\":1},\"pkg\":\"com.samsung.android.messaging\"}"
    private val exp = 1_900_000_000_000L
    private val now = exp - 60_000

    private fun body(c: String = canon, nonce: String = "abcdefghijklmnop", e: Long = exp) =
        buildJsonObject { put("canon", c); put("nonce", nonce); put("exp", e) }

    @Test
    fun `key, digest and signature match the python bridge`() {
        val key = AppFunctionConfirmHandler.confirmKey(master)
        assertThat(key.joinToString("") { "%02x".format(it) })
            .isEqualTo("da835916bb04e597e980440bc1ca356079de7b2b2d1930761044d5b65a509a96")
        val req = AppFunctionConfirmHandler.parse(body(), now)!!
        val out = Json.parseToJsonElement(AppFunctionConfirmHandler.resultJson(key, req, true)).jsonObject
        assertThat(out["digest"]!!.jsonPrimitive.content)
            .isEqualTo("1ce8083f3cf28918dc36cb03bbb050257944b78036bfc404cf6c487b33cd1cf0")
        assertThat(out["sig"]!!.jsonPrimitive.content)
            .isEqualTo("a71023951c30487732f121d5d0238e07f84d27d9a0a46c70aca885d8931c14c4")
    }

    @Test
    fun `denied result carries no signature`() {
        val req = AppFunctionConfirmHandler.parse(body(), now)!!
        val out = Json.parseToJsonElement(
            AppFunctionConfirmHandler.resultJson(AppFunctionConfirmHandler.confirmKey(master), req, false)).jsonObject
        assertThat(out["approved"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(out["sig"]!!.jsonPrimitive.content).isEmpty()
    }

    @Test
    fun `strict parsing`() {
        assertThat(AppFunctionConfirmHandler.parse(null, now)).isNull()
        assertThat(AppFunctionConfirmHandler.parse(body(e = now - 1), now)).isNull()                 // expired
        assertThat(AppFunctionConfirmHandler.parse(body(e = now + 3_600_000), now)).isNull()         // too far ahead
        assertThat(AppFunctionConfirmHandler.parse(body(nonce = "short"), now)).isNull()
        assertThat(AppFunctionConfirmHandler.parse(body(c = "{\"pkg\":\"a\",\"fn\":\"b\"}"), now)).isNull()
        assertThat(AppFunctionConfirmHandler.parse(body(c = "{\"pkg\":\"a\",\"fn\":\"b\",\"params\":{},\"x\":1}"), now)).isNull()
        assertThat(AppFunctionConfirmHandler.parse(body(c = "not json"), now)).isNull()
    }

    @Test
    fun `card is remote and tap-only, the owner's tap decides`() = runTest {
        val manager = ToolConfirmationManager()
        val settings = mockk<JarvisLinkSettingsStore> { every { signingKey() } returns master }
        val handler = AppFunctionConfirmHandler(manager, settings)
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
