package io.androllm.core.jarvistools.tesla

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class TeslaToolsTest {

    private val transport = FakeTransport()
    private val secrets = FakeSecrets(mutableMapOf(JarvisSecret.TESLA_API_TOKEN to TEST_TOKEN))
    private val base = "https://tesla.example"
    private val client = TeslaBridgeClient(transport, secrets, StaticEndpoints(teslaBaseUrl = "$base/"))
    private val gate = FakeGate()
    private val command = TeslaCommandTool(client, gate)
    private val status = TeslaStatusTool(client)

    private fun action(a: String) = buildJsonObject { put("action", a) }

    @Test fun confirmationFlags() {
        assertThat(command.spec.requiresConfirmation).isTrue()
        assertThat(status.spec.requiresConfirmation).isFalse()
        assertThat(command.spec.cacheable).isFalse()
    }

    @Test fun schemaEnumOnlyContainsAllowlist() {
        val props = command.spec.parameters["properties"] as JsonObject
        val enum = ((props["action"] as JsonObject)["enum"] as JsonArray).map { (it as JsonPrimitive).content }
        assertThat(enum).containsExactlyElementsIn(TeslaSafetyPolicy.ALLOWED)
        assertThat(enum).containsNoneOf("unlock", "remote_start", "open_trunk", "open_frunk")
    }

    @Test fun statusRequestBuilt() = runTest {
        transport.response = HttpResponse(
            200,
            """{"ok":true,"data":{"battery_level":77,"range_km":350,"locked":true,"latitude":40.1,"longitude":49.8,"age_s":120}}"""
        )
        val r = status.execute(buildJsonObject { })
        assertThat(r).isInstanceOf(ToolResult.Success::class.java)
        val req = transport.requests.single()
        assertThat(req.method).isEqualTo("GET")
        assertThat(req.url).isEqualTo("$base/api/status")
        assertThat(req.headers["Authorization"]).isEqualTo("Bearer $TEST_TOKEN")
        val data = (r as ToolResult.Success).data
        assertThat(data.keys).containsNoneOf("latitude", "longitude")
        assertThat(r.summary).contains("battery 77%")
    }

    @Test fun freshStatus() = runTest {
        transport.response = HttpResponse(200, """{"ok":true,"data":{}}""")
        status.execute(buildJsonObject { put("fresh", true) })
        assertThat(transport.requests.single().url).isEqualTo("$base/api/status?fresh=true")
    }

    @Test fun commandRequestBuiltAfterConfirmation() = runTest {
        val r = command.execute(buildJsonObject { put("action", "set_temp"); put("temp_c", 22) })
        assertThat(r).isInstanceOf(ToolResult.Success::class.java)
        assertThat(gate.asked).containsExactly("tesla_command")
        val req = transport.requests.single()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.url).isEqualTo("$base/api/command/set_temp")
        assertThat(req.jsonBody).isEqualTo("""{"temp_c":22.0}""")
        assertThat(req.headers["Authorization"]).isEqualTo("Bearer $TEST_TOKEN")
    }

    @Test fun forbiddenCommandsNeverReachNetworkOrConfirmation() = runTest {
        for (a in listOf("unlock", "remote_start", "open_trunk", "open_frunk", "charge_port_open", "window_control")) {
            val r = command.execute(action(a))
            assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
            assertThat((r as ToolResult.Failure).retryable).isFalse()
        }
        assertThat(transport.requests).isEmpty()
        assertThat(gate.asked).isEmpty()
    }

    @Test fun clientCannotBuildForbiddenRequest() {
        for (a in listOf("unlock", "remote_start", "open_trunk", "open_frunk")) {
            var threw = false
            try {
                client.buildCommandRequest(TEST_TOKEN, TeslaSafetyPolicy.Decision.Allowed(a, emptyMap()))
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertThat(threw).isTrue()
        }
    }

    @Test fun declinedConfirmationSendsNothing() = runTest {
        gate.approve = false
        val r = command.execute(action("honk"))
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(transport.requests).isEmpty()
    }

    @Test fun missingTokenFailsWithoutNetwork() = runTest {
        secrets.clear(JarvisSecret.TESLA_API_TOKEN)
        val r = status.execute(buildJsonObject { })
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(transport.requests).isEmpty()
    }

    @Test fun badTokenMapped() = runTest {
        transport.response = HttpResponse(401, """{"detail":"bad token"}""")
        val r = status.execute(buildJsonObject { }) as ToolResult.Failure
        assertThat(r.summary).contains("token")
        assertThat(r.summary).doesNotContain(TEST_TOKEN)
    }

    @Test fun bridgeForbiddenIsNotRetryable() = runTest {
        transport.response = HttpResponse(403, """{"ok":false,"action":"honk","error":"forbidden"}""")
        val r = command.execute(action("honk")) as ToolResult.Failure
        assertThat(r.retryable).isFalse()
        assertThat(r.summary).contains("safety")
    }

    @Test fun requestToStringHidesToken() {
        assertThat(client.buildStatusRequest(TEST_TOKEN, false).toString()).doesNotContain(TEST_TOKEN)
    }
}
