package io.androllm.core.jarvistools.whatsapp

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvistools.FakeGate
import io.androllm.core.jarvistools.FakeSecrets
import io.androllm.core.jarvistools.FakeTransport
import io.androllm.core.jarvistools.TEST_TOKEN
import io.androllm.core.jarvistools.net.HttpResponse
import io.androllm.core.jarvistools.secrets.JarvisSecret
import io.androllm.core.jarvistools.settings.StaticEndpoints
import io.androllm.core.tools.api.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class WhatsAppToolsTest {

    private val transport = FakeTransport()
    private val secrets = FakeSecrets(mutableMapOf(JarvisSecret.WHATSAPP_API_TOKEN to TEST_TOKEN))
    private val base = "http://10.0.0.5:8090"
    private val client = WhatsAppBotClient(transport, secrets, StaticEndpoints(whatsappBaseUrl = base))
    private val gate = FakeGate()

    @Test fun flags() {
        assertThat(WhatsAppStatusTool(client).spec.requiresConfirmation).isFalse()
        assertThat(WhatsAppPendingTool(client).spec.requiresConfirmation).isFalse()
        assertThat(WhatsAppInboxTool(client).spec.requiresConfirmation).isFalse()
        assertThat(WhatsAppReplyTool(client, gate).spec.requiresConfirmation).isTrue()
    }

    @Test fun inboxRequestClampsLimit() = runTest {
        transport.response = HttpResponse(200, """{"items":[{"phone":"994000","name":"Ali","text":"salam","kind":"text","ts":1}]}""")
        val r = WhatsAppInboxTool(client).execute(buildJsonObject { put("limit", 500) })
        assertThat(r).isInstanceOf(ToolResult.Success::class.java)
        val req = transport.requests.single()
        assertThat(req.method).isEqualTo("GET")
        assertThat(req.url).isEqualTo("$base/api/inbox?limit=50")
        assertThat(req.headers["Authorization"]).isEqualTo("Bearer $TEST_TOKEN")
        assertThat(r.summary).contains("Ali: salam")
    }

    @Test fun pendingRendersOptions() = runTest {
        transport.response = HttpResponse(
            200,
            """{"items":[{"id":7,"phone":"994","name":"Ali","summary":"asks time","options":["at 5","tomorrow"],"urgency":"normal","created":1}]}"""
        )
        val r = WhatsAppPendingTool(client).execute(buildJsonObject { })
        assertThat(transport.requests.single().url).isEqualTo("$base/api/pending")
        assertThat(r.summary).contains("#7 Ali")
        assertThat(r.summary).contains("1) at 5")
    }

    @Test fun replyWithOptionBuildsBodyAfterConfirmation() = runTest {
        transport.response = HttpResponse(200, """{"ok":true,"to":"Ali","text":"at 5"}""")
        val r = WhatsAppReplyTool(client, gate).execute(buildJsonObject { put("id", 7); put("option", 1) })
        assertThat(r).isInstanceOf(ToolResult.Success::class.java)
        assertThat(gate.asked).containsExactly("whatsapp_reply")
        val req = transport.requests.single()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.url).isEqualTo("$base/api/reply")
        assertThat(req.jsonBody).isEqualTo("""{"id":7,"option":1}""")
    }

    @Test fun replyWithText() {
        val req = client.buildReplyRequest(TEST_TOKEN, 9, null, "  ok, see you  ")
        assertThat(req.jsonBody).isEqualTo("""{"id":9,"text":"ok, see you"}""")
    }

    @Test fun replyNeedsExactlyOneOfOptionOrText() = runTest {
        val tool = WhatsAppReplyTool(client, gate)
        val both = tool.execute(buildJsonObject { put("id", 7); put("option", 1); put("text", "x") })
        val none = tool.execute(buildJsonObject { put("id", 7) })
        assertThat(both).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(none).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(transport.requests).isEmpty()
        assertThat(gate.asked).isEmpty()
    }

    @Test fun declinedReplySendsNothing() = runTest {
        gate.approve = false
        val r = WhatsAppReplyTool(client, gate).execute(buildJsonObject { put("id", 7); put("option", 2) })
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(transport.requests).isEmpty()
    }

    @Test fun conflictErrorSurfaced() = runTest {
        transport.response = HttpResponse(409, """{"ok":false,"error":"already handled"}""")
        val r = WhatsAppReplyTool(client, gate).execute(buildJsonObject { put("id", 7); put("option", 1) }) as ToolResult.Failure
        assertThat(r.summary).contains("already handled")
        assertThat(r.retryable).isFalse()
    }

    @Test fun missingTokenNoNetwork() = runTest {
        secrets.clear(JarvisSecret.WHATSAPP_API_TOKEN)
        val r = WhatsAppStatusTool(client).execute(buildJsonObject { })
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(transport.requests).isEmpty()
    }
}
