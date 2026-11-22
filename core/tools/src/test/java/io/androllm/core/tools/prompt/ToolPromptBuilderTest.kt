package io.androllm.core.tools.prompt

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.planner.ToolPlanner
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ToolPromptBuilderTest {

    private val weather = ToolSpec(
        name = "get_weather",
        description = "Get current weather for a city.",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties", buildJsonObject {
                    put("city", buildJsonObject { put("type", "string") })
                    put("units", buildJsonObject { put("type", "string") })
                }
            )
            put("required", buildJsonArray { add(JsonPrimitive("city")) })
        },
        category = ToolCategory.INFORMATION
    )

    private val flashlight = ToolSpec(
        name = "set_flashlight",
        description = "Turn the flashlight on or off.",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties", buildJsonObject {
                    put("on", buildJsonObject { put("type", "boolean") })
                }
            )
            put("required", buildJsonArray { add(JsonPrimitive("on")) })
        },
        category = ToolCategory.DEVICE
    )

    @Test
    fun `advertisement returns null when the pipeline reports no tools`() = runTest {
        val planner = mockk<ToolPlanner>()
        coEvery { planner.routedTools(any(), any()) } returns emptyList()
        val builder = ToolPromptBuilder(planner)

        assertThat(builder.advertisement()).isNull()
    }

    @Test
    fun `render lists every tool with description arguments and example`() {
        val builder = ToolPromptBuilder(mockk(relaxed = true))
        val text = builder.render(listOf(weather, flashlight))

        // The model is told it HAS tools and must never claim otherwise.
        assertThat(text).contains("you HAVE access to tools")
        assertThat(text).contains("never claim you lack access")
        assertThat(text).contains("AVAILABLE TOOLS")

        // weather: description, argument types and a required-field example.
        assertThat(text).contains("- get_weather")
        assertThat(text).contains("Get current weather for a city.")
        assertThat(text).contains("city (string)")
        assertThat(text).contains("units (string)")
        assertThat(text).contains("example: {\"city\": \"value\"}")

        // flashlight: boolean placeholder in the example.
        assertThat(text).contains("- set_flashlight")
        assertThat(text).contains("on (boolean)")
        assertThat(text).contains("example: {\"on\": true}")
    }

    @Test
    fun `advertisement wraps the rendered block`() = runTest {
        val planner = mockk<ToolPlanner>()
        coEvery { planner.routedTools(any(), any()) } returns listOf(weather)
        val builder = ToolPromptBuilder(planner)

        val ad = builder.advertisement()
        assertThat(ad).isNotNull()
        assertThat(ad).contains("- get_weather")
    }

    // ── Н10: enum values are listed so a cut description hides no action ──

    private val tesla = ToolSpec(
        name = "tesla_command",
        description = "Send a SAFE command to the owner's Tesla via tesla-bridge (always asks the user first): climate_on, climate_off, set_temp (temp_c 15..28), lock, charge_start, charge_stop, set_charge_limit (percent 50..100). Unlocking is NOT possible.",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties", buildJsonObject {
                    put(
                        "action", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum", buildJsonArray {
                                    listOf(
                                        "charge_start", "charge_stop", "climate_off", "climate_on",
                                        "lock", "set_charge_limit", "set_temp"
                                    ).forEach { add(JsonPrimitive(it)) }
                                }
                            )
                        }
                    )
                    put("temp_c", buildJsonObject { put("type", "number") })
                }
            )
            put("required", buildJsonArray { add(JsonPrimitive("action")) })
        },
        category = ToolCategory.DEVICE
    )

    @Test
    fun `enum arguments list every allowed value even when the description is cut`() {
        val builder = ToolPromptBuilder(mockk(relaxed = true))
        for (budget in listOf(0, 1200)) {
            val text = builder.render(listOf(tesla), maxChars = budget)
            for (action in listOf("lock", "charge_start", "charge_stop", "set_charge_limit", "climate_on", "set_temp")) {
                assertThat(text).contains(action)
            }
            assertThat(text).contains("action (string: charge_start|charge_stop|climate_off|climate_on|lock|set_charge_limit|set_temp)")
        }
    }

    // ── Н4: tight budgets switch to compact lines before dropping tools ──

    private fun synthetic(count: Int): List<ToolSpec> = (1..count).map { i ->
        ToolSpec(
            name = "tool_%02d".format(i),
            description = "Synthetic tool number $i that does something useful for the user on the device. ".repeat(3),
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties", buildJsonObject {
                        put("query", buildJsonObject { put("type", "string") })
                        put("limit", buildJsonObject { put("type", "integer") })
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
            },
            category = ToolCategory.INFORMATION
        )
    }

    @Test
    fun `unbudgeted render keeps the full format`() {
        val builder = ToolPromptBuilder(mockk(relaxed = true))
        val specs = synthetic(63)
        val text = builder.render(specs)
        assertThat(text).contains("- tool_63")
        assertThat(text).contains("example: {\"query\": \"value\"}")
        assertThat(text.length).isGreaterThan(10_000)
    }

    @Test
    fun `budgeted render never exceeds the budget and fits more tools compactly`() {
        val builder = ToolPromptBuilder(mockk(relaxed = true))
        val specs = synthetic(63)
        for (ctx in listOf(2048, 4096)) {
            val budget = ToolAdvertisementBudget.charsFor(ctx)
            val text = builder.render(specs, maxChars = budget)
            assertThat(text.length).isAtMost(budget)
            // Most relevant (first) tools always survive.
            assertThat(text).contains("- tool_01")
            // Compact lines: no examples, so more tools than the full format would fit.
            assertThat(text).doesNotContain("example:")
            val compactCount = Regex("(?m)^- tool_").findAll(text).count()
            val fullPerLine = builder.render(specs.take(1)).length - builder.render(emptyList()).length
            val fullCapacity = (budget - builder.render(emptyList()).length) / fullPerLine
            assertThat(compactCount).isGreaterThan(fullCapacity)
        }
    }

    @Test
    fun `a budget that fits everything keeps the full format`() {
        val builder = ToolPromptBuilder(mockk(relaxed = true))
        val specs = listOf(weather, flashlight)
        val text = builder.render(specs, maxChars = 50_000)
        assertThat(text).isEqualTo(builder.render(specs))
    }

    @Test
    fun `advertisement forwards budget and query to the router`() = runTest {
        val planner = mockk<ToolPlanner>()
        coEvery { planner.routedTools("закрой машину", false) } returns listOf(tesla) + synthetic(62)
        val builder = ToolPromptBuilder(planner)
        val budget = ToolAdvertisementBudget.charsFor(4096)
        val ad = builder.advertisement(maxChars = budget, query = "закрой машину")!!
        assertThat(ad.length).isAtMost(budget)
        assertThat(ad).contains("- tesla_command")
        assertThat(ad).contains("lock")
    }
}
