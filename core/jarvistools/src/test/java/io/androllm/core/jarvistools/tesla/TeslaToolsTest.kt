package io.androllm.core.jarvistools.tesla

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvistools.FakeGate
import io.androllm.core.jarvistools.FakeSecrets
import io.androllm.core.jarvistools.FakeTransport
import io.androllm.core.jarvistools.TEST_TOKEN
import io.androllm.core.jarvistools.confirm.SideEffectBudget
import io.androllm.core.jarvistools.net.HttpResponse
import io.androllm.core.jarvistools.settings.StaticEndpoints
import io.androllm.core.jarvistools.vault.JarvisSecret
import io.androllm.core.tools.api.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class TeslaToolsTest {

    private val transport = FakeTransport()
    private val origin = "https://tesla.example:443"
    private val secrets = FakeSecrets().apply { put(JarvisSecret.TESLA_API_TOKEN, TEST_TOKEN, origin) }
    private var endpoints = StaticEndpoints(teslaBaseUrl = "https://tesla.example/")
    private val client get() = TeslaBridgeClient(transport, secrets, endpoints)
    private val gate = FakeGate()
    private val command get() = TeslaCommandTool(client, gate)
    private val status get() = TeslaStatusTool(client)

    private fun action(a: String) = buildJsonObject { put("action", a) }

    @Test fun flags() {
        assertThat(command.spec.requiresConfirmation).isTrue()
        assertThat(status.spec.requiresConfirmation).isFalse()
        assertThat(command.spec.cacheable).isFalse()
        assertThat(status.spec.cacheable).isFalse()
        assertThat(command.spec.executionTimeoutMs!!).isAtLeast(SideEffectBudget.CONFIRM_MS + SideEffectBudget.NET_MS)
    }

    @Test fun schemaEnumIsExactlyTierA() {
        val props = command.spec.parameters["properties"] as JsonObject
        val enum = ((props["action"] as JsonObject)["enum"] as JsonArray).map { (it as JsonPrimitive).content }
        assertThat(enum).containsExactly(
            "charge_start", "charge_stop", "climate_off", "climate_on", "lock", "set_charge_limit", "set_temp"
        )
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
        assertThat(req.url.toString()).isEqualTo("https://tesla.example/api/status")
        assertThat(req.headers["Authorization"]).isEqualTo("Bearer $TEST_TOKEN")
        val data = (r as ToolResult.Success).data
        assertThat(data.keys).containsNoneOf("latitude", "longitude")
        assertThat(r.summary).contains("battery 77%")
    }

    @Test fun freshStatus() = runTest {
        transport.response = HttpResponse(200, """{"ok":true,"data":{}}""")
        status.execute(buildJsonObject { put("fresh", true) })
        assertThat(transport.requests.single().url.toString()).isEqualTo("https://tesla.example/api/status?fresh=true")
    }

    @Test fun commandRequestBuiltAfterConfirmation() = runTest {
        val r = command.execute(buildJsonObject { put("action", "set_temp"); put("temp_c", 22) })
        assertThat(r).isInstanceOf(ToolResult.Success::class.java)
        assertThat(gate.asked).containsExactly("tesla_command")
        val req = transport.requests.single()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.url.toString()).isEqualTo("https://tesla.example/api/command/set_temp")
        assertThat(req.jsonBody).isEqualTo("""{"temp_c":22.0}""")
    }

    @Test fun forbiddenCommandsNeverReachNetworkOrConfirmation() = runTest {
        for (a in listOf("unlock", "remote_start", "open_trunk", "open_frunk", "charge_port_open", "window_control", "honk", "flash_lights")) {
            val r = command.execute(action(a))
            assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
            assertThat((r as ToolResult.Failure).retryable).isFalse()
        }
        assertThat(transport.requests).isEmpty()
        assertThat(gate.asked).isEmpty()
    }

    @Test fun clientCannotBuildForbiddenRequest() {
        val base = "https://tesla.example/".toHttpUrl()
        for (a in listOf("unlock", "remote_start", "open_trunk", "open_frunk", "honk")) {
            var threw = false
            try {
                client.buildCommandRequest(base, TEST_TOKEN, TeslaSafetyPolicy.Decision.Allowed(a, emptyMap()))
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertThat(threw).isTrue()
        }
    }

    @Test fun craftedBaseUrlsAreRefusedWithoutNetwork() = runTest {
        val bad = listOf(
            "https://tesla.example/api/command/charge_port_open?x=",
            "https://tesla.example/api/command/open_trunk#",
            "http://tesla.example",
            "https://user:pw@tesla.example",
            "https://tesla.example/prefix",
            "ftp://tesla.example",
            "not a url"
        )
        for (b in bad) {
            endpoints = StaticEndpoints(teslaBaseUrl = b)
            val r = command.execute(action("lock"))
            assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
            assertThat(status.execute(buildJsonObject { })).isInstanceOf(ToolResult.Failure::class.java)
        }
        assertThat(transport.requests).isEmpty()
        assertThat(gate.asked).isEmpty()
    }

    @Test fun otherOriginNeverGetsTheToken() = runTest {
        // Valid URL, but a different origin than the one the token was entered for.
        endpoints = StaticEndpoints(teslaBaseUrl = "https://evil.example")
        val r = status.execute(buildJsonObject { })
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(transport.requests).isEmpty()
        endpoints = StaticEndpoints(teslaBaseUrl = "https://tesla.example:8443")
        assertThat(status.execute(buildJsonObject { })).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(transport.requests).isEmpty()
    }

    @Test fun commandPathIsAlwaysOneOfTheAllowed() {
        val base = "https://tesla.example".toHttpUrl()
        for (a in TeslaSafetyPolicy.ALLOWED) {
            val req = client.buildCommandRequest(base, TEST_TOKEN, TeslaSafetyPolicy.Decision.Allowed(a, emptyMap()))
            assertThat(req.url.encodedPath).isEqualTo("/api/command/$a")
            assertThat(req.url.query).isNull()
            assertThat(req.url.host).isEqualTo("tesla.example")
        }
    }

    @Test fun declinedConfirmationSendsNothing() = runTest {
        gate.approve = false
        val r = command.execute(action("lock"))
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(transport.requests).isEmpty()
    }

    @Test fun missingTokenFailsBeforeConfirmation() = runTest {
        secrets.clear(JarvisSecret.TESLA_API_TOKEN)
        assertThat(command.execute(action("lock"))).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(status.execute(buildJsonObject { })).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(transport.requests).isEmpty()
        assertThat(gate.asked).isEmpty()
    }

    @Test fun hugePercentIsNotTruncated() = runTest {
        val r = command.execute(buildJsonObject { put("action", "set_charge_limit"); put("percent", 4294967297L) })
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(gate.asked).isEmpty()
    }

    @Test fun badTokenMappedAndNotRetryable() = runTest {
        transport.response = HttpResponse(401, """{"detail":"bad token"}""")
        val r = status.execute(buildJsonObject { }) as ToolResult.Failure
        assertThat(r.summary).contains("token")
        assertThat(r.summary).doesNotContain(TEST_TOKEN)
        assertThat(r.retryable).isFalse()
    }

    @Test fun bridgeForbiddenIsNotRetryable() = runTest {
        transport.response = HttpResponse(403, """{"ok":false,"action":"lock","error":"forbidden"}""")
        val r = command.execute(action("lock")) as ToolResult.Failure
        assertThat(r.retryable).isFalse()
        assertThat(r.summary).contains("safety")
    }

    @Test fun lateConfirmationSendsNothing() {
        val t = SideEffectBudget.ONE_CALL_MS
        assertThat(SideEffectBudget.hasNetworkBudget(0, t, nowMs = 1_000)).isTrue()
        assertThat(SideEffectBudget.hasNetworkBudget(0, t, nowMs = SideEffectBudget.CONFIRM_MS + 1_000)).isTrue()
        assertThat(SideEffectBudget.hasNetworkBudget(0, t, nowMs = t - SideEffectBudget.NET_MS)).isFalse()
    }

    @Test fun requestToStringHidesToken() {
        assertThat(client.buildStatusRequest("https://tesla.example".toHttpUrl(), TEST_TOKEN, false).toString())
            .doesNotContain(TEST_TOKEN)
    }
}
