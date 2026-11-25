package io.androllm.engine.core

import io.androllm.engine.models.NativeToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch P: canned model outputs → parsed tool calls, per family.
 * Step 4 on the phone: `nativeCalls=0` on every local model — Qwen's official
 * `<tool_call>` tags were not recognised and Gemma 4's native argument
 * syntax (`<|"|>` strings, unquoted keys) degraded to `{}`.
 */
class LocalToolCallParsingTest {

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `qwen3 official tool_call output yields a tool call`() {
        val raw = "<tool_call>\n{\"name\": \"set_wifi\", \"arguments\": {\"enabled\": true}}\n</tool_call>"
        val calls = NativeToolCallScanner.scan(raw)
        assertEquals(1, calls.size)
        assertEquals("set_wifi", calls[0].name)
        assertEquals(true, args(calls[0].argumentsJson)["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `qwen3 output with text before the call and two calls`() {
        val raw = "Opening it.\n<tool_call>\n{\"name\": \"launch_app\", \"arguments\": {\"app\": \"Settings\"}}\n</tool_call>\n" +
            "<tool_call>\n{\"name\": \"get_battery\", \"arguments\": {}}\n</tool_call>"
        val calls = NativeToolCallScanner.scan(raw)
        assertEquals(listOf("launch_app", "get_battery"), calls.map { it.name })
        assertEquals("Settings", args(calls[0].argumentsJson)["app"]!!.jsonPrimitive.content)
        assertEquals("Opening it.", NativeToolCallScanner.strip(raw))
    }

    @Test
    fun `gemma4 native call with quoted string arguments yields json arguments`() {
        val raw = "<|tool_call>call:set_wifi{enabled:true,note:<|\"|>open the Wi-Fi page<|\"|>,level:3}<tool_call|>"
        val calls = NativeToolCallScanner.scan(raw)
        assertEquals(1, calls.size)
        assertEquals("set_wifi", calls[0].name)
        val a = args(calls[0].argumentsJson)
        assertEquals(true, a["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("open the Wi-Fi page", a["note"]!!.jsonPrimitive.content)
        assertEquals(3, a["level"]!!.jsonPrimitive.int)
    }

    @Test
    fun `gemma4 nested object and array arguments`() {
        val json = NativeToolCallScanner.Gemma4Args.toJson(
            "{to:[<|\"|>a<|\"|>,<|\"|>b<|\"|>],opts:{loud:false}}"
        )
        assertEquals("{\"to\":[\"a\",\"b\"],\"opts\":{\"loud\":false}}", json)
    }

    @Test
    fun `runtime parsed gemma4 call converts to a native tool call`() {
        // What LiteRT-LM's Gemma4DataProcessor hands back in Message.toolCalls
        // (Gson decodes JSON numbers as Double).
        val call = NativeToolDeclarations.toNativeToolCall(
            "set_wifi",
            mapOf("enabled" to true, "level" to 3.0, "tags" to listOf("x"))
        )
        assertEquals("set_wifi", call.name)
        assertEquals("{\"enabled\":true,\"level\":3,\"tags\":[\"x\"]}", call.argumentsJson)
    }

    @Test
    fun `native tool description follows the OpenApiTool contract`() {
        val json = NativeToolDeclarations.descriptionJson(
            NativeToolSpec("set_wifi", "Turn Wi-Fi on or off", "{\"type\":\"object\",\"properties\":{\"enabled\":{\"type\":\"boolean\"}}}")
        )
        val obj = args(json)
        assertEquals("set_wifi", obj["name"]!!.jsonPrimitive.content)
        assertTrue(obj["parameters"]!!.jsonObject.containsKey("properties"))
    }

    @Test
    fun `native tool budget keeps the most relevant tools and at least one`() {
        val specs = (1..10).map { NativeToolSpec("t$it", "d".repeat(100), "{}") }
        val fitted = NativeToolDeclarations.fitBudget(specs, maxChars = 450)
        assertTrue(fitted.size in 1..4)
        assertEquals("t1", fitted.first().name)
        assertEquals(1, NativeToolDeclarations.fitBudget(specs, maxChars = 1).size)
        assertEquals(10, NativeToolDeclarations.fitBudget(specs, maxChars = 0).size)
    }
}
