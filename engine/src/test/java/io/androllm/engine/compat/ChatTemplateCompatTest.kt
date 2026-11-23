package io.androllm.engine.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatTemplateCompat.partsAware] must (1) render byte-identically to the
 * original template for string content (LiteRT-LM 0.16 / app renderer) and
 * (2) render the same prompt when content arrives as LiteRT-LM >= 0.18's list
 * of parts.
 */
class ChatTemplateCompatTest {

    private val renderer = ChatTemplateRenderer()

    private val families = mapOf(
        "gemmaLenient" to ChatTemplates.gemmaLenient,
        "qwen" to ChatTemplates.qwen,
        "qwen3" to ChatTemplates.qwen3,
        "llama3" to ChatTemplates.llama3,
        "phi" to ChatTemplates.phi,
        "mistral" to ChatTemplates.mistral,
        "deepseek" to ChatTemplates.deepseek,
        "tinyLlama" to ChatTemplates.tinyLlama
    )

    private fun turns(withSystem: Boolean) = buildList {
        if (withSystem) add("system" to "Be brief.")
        add("user" to "Hi")
        add("assistant" to "Hello!")
        add("user" to "Again")
    }

    private fun asStrings(t: List<Pair<String, String>>) =
        t.map { ChatTemplateRenderer.RenderMessage(it.first, it.second) }

    private fun asParts(t: List<Pair<String, String>>): List<Map<String, Any?>> = t.map { (role, text) ->
        mapOf(
            "role" to role,
            // A non-text part must be skipped; text parts are concatenated.
            "content" to listOf(
                mapOf("type" to "text", "text" to text.take(2)),
                mapOf("type" to "image"),
                mapOf("type" to "text", "text" to text.drop(2))
            )
        )
    }

    @Test
    fun `string content renders byte-identically to the original template`() {
        for ((name, t) in families) {
            val withSystem = name != "mistral"
            val msgs = asStrings(turns(withSystem))
            val expected = renderer.render(t, msgs, bosToken = "<s>", eosToken = "</s>", addGenerationPrompt = true)
            val actual = renderer.render(
                ChatTemplateCompat.partsAware(t), msgs, bosToken = "<s>", eosToken = "</s>", addGenerationPrompt = true
            )
            assertEquals(name, expected, actual)
        }
    }

    @Test
    fun `list-of-parts content renders the same prompt as string content`() {
        for ((name, t) in families) {
            val withSystem = name != "mistral"
            val expected = renderer.render(
                t, asStrings(turns(withSystem)), bosToken = "<s>", eosToken = "</s>", addGenerationPrompt = true
            )
            val actual = renderer.render(
                ChatTemplateCompat.partsAware(t),
                emptyList(),
                bosToken = "<s>",
                eosToken = "</s>",
                addGenerationPrompt = true,
                extraContext = mapOf("messages" to asParts(turns(withSystem)))
            )
            assertEquals(name, expected, actual)
        }
    }

    @Test
    fun `rewrite is idempotent and leaves templates without content refs alone`() {
        val once = ChatTemplateCompat.partsAware(ChatTemplates.qwen3)
        assertEquals(once, ChatTemplateCompat.partsAware(once))
        assertTrue(!once.contains("message['content'] +"))
        val noContent = "{{ bos_token }}"
        assertEquals(noContent, ChatTemplateCompat.partsAware(noContent))
    }
}
