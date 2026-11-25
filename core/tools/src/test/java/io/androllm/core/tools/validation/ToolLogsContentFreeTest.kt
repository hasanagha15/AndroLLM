package io.androllm.core.tools.validation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.common.log.ContentLog
import io.androllm.core.tools.api.ToolSpec
import io.mockk.mockk
import java.util.Collections
import org.junit.After
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * Branch Q (gate B3): validation / streaming / heuristic logs of core:tools
 * carry no model output, tool arguments or user words.
 */
class ToolLogsContentFreeTest {

    private val captured = Collections.synchronizedList(mutableListOf<String>())
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            captured += message
        }
    }

    @Before
    fun plant() {
        ContentLog.enabled = false
        Timber.plant(tree)
    }

    @After
    fun uproot() {
        Timber.uproot(tree)
        ContentLog.enabled = false
    }

    private fun assertNoLeak(vararg markers: String) {
        val logs = captured.toList()
        assertWithMessage("nothing was logged").that(logs).isNotEmpty()
        for (m in markers) {
            assertWithMessage("'$m' leaked:\n" + logs.filter { m in it }.joinToString("\n"))
                .that(logs.none { m in it }).isTrue()
        }
    }

    @Test
    fun `malformed JSON error names the offset, never the input`() {
        val r = JsonSchemaValidator.validateJsonSyntax("""{"to":"Q7SECRET" "body":1}""") as ValidationResult.Invalid
        assertThat(r.firstError).startsWith("Malformed JSON")
        assertThat(r.firstError).doesNotContain("Q7SECRET")
        assertThat(r.firstError).doesNotContain("JSON input")
    }

    @Test
    fun `logValidation never prints error text`() {
        val logger = ToolExecutionLogger()
        logger.logValidation("<malformed>", JsonSchemaValidator.validateJsonSyntax("""{"q":"Q7SECRET" x}"""))
        logger.logValidation("send_sms", ValidationResult.Invalid("value 'Q7ARGVALUE' rejected"))
        assertNoLeak("Q7SECRET", "Q7ARGVALUE")
    }

    @Test
    fun `streaming buffer does not log malformed arguments`() {
        val buffer = StreamingToolCallBuffer()
        buffer.accumulate(CloudStreamEvent.ToolCallDelta(0, "c1", "send_sms", """{"body": Q7SECRET}"""))
        buffer.flushOnFinish()
        assertNoLeak("Q7SECRET")
    }

    @Test
    fun `heuristic fallback logs no expression or location`() {
        val pipeline = ToolCallingPipeline(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val calc = pipeline.heuristicFallback("calculate 4817+2290", listOf(ToolSpec(name = "calculate", description = "math")))
        assertThat(calc).hasSize(1)
        val weather = pipeline.heuristicFallback("weather in Qsevencity", listOf(ToolSpec(name = "get_weather", description = "weather")))
        assertThat(weather).hasSize(1)
        assertNoLeak("4817", "Qsevencity")
    }
}
