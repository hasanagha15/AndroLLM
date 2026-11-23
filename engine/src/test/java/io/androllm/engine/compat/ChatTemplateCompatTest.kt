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
        "mistralLenient" to ChatTemplates.mistralLenient,
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

    // ---- N2: more shapes ----------------------------------------------------

    private fun bothShapesAgree(name: String, t: List<Pair<String, String>>) {
        val tmpl = families.getValue(name)
        val expected = renderer.render(tmpl, asStrings(t), bosToken = "<s>", eosToken = "</s>", addGenerationPrompt = true)
        val asString = renderer.render(
            ChatTemplateCompat.partsAware(tmpl), asStrings(t), bosToken = "<s>", eosToken = "</s>", addGenerationPrompt = true
        )
        val asParts = renderer.render(
            ChatTemplateCompat.partsAware(tmpl), emptyList(), bosToken = "<s>", eosToken = "</s>",
            addGenerationPrompt = true, extraContext = mapOf("messages" to asParts(t))
        )
        assertEquals("$name string", expected, asString)
        assertEquals("$name parts", expected, asParts)
    }

    @Test
    fun `no system message - qwen default system line appears exactly once`() {
        val t = listOf("user" to "Hi", "assistant" to "Hello!", "user" to "Again")
        for (name in families.keys) bothShapesAgree(name, t)
        val out = renderer.render(
            ChatTemplateCompat.partsAware(ChatTemplates.qwen), emptyList(), addGenerationPrompt = true,
            extraContext = mapOf("messages" to asParts(t))
        )
        assertEquals(1, Regex("You are a helpful assistant").findAll(out).count())
    }

    @Test
    fun `single user turn renders the same for both shapes`() {
        for (name in families.keys) bothShapesAgree(name, listOf("user" to "Hello"))
    }

    @Test
    fun `null content takes the is-none path like the original`() {
        // qwen3 / deepseek check `content is none` for assistant tool-call turns.
        val msgs = listOf(
            mapOf("role" to "user", "content" to "Hi"),
            mapOf("role" to "assistant", "content" to null, "tool_calls" to "{\"name\":\"t\"}"),
            mapOf("role" to "user", "content" to "Again")
        )
        for (name in listOf("qwen3", "deepseek")) {
            val t = families.getValue(name)
            val original = renderer.render(t, emptyList(), addGenerationPrompt = true, extraContext = mapOf("messages" to msgs))
            val compat = renderer.render(
                ChatTemplateCompat.partsAware(t), emptyList(), addGenerationPrompt = true,
                extraContext = mapOf("messages" to msgs)
            )
            assertEquals(name, original, compat)
        }
    }

    @Test
    fun `image-only content flattens to an empty turn instead of failing`() {
        val msgs = listOf(
            mapOf("role" to "user", "content" to listOf(mapOf("type" to "image"))),
        )
        val out = renderer.render(
            ChatTemplateCompat.partsAware(ChatTemplates.qwen3), emptyList(), addGenerationPrompt = true,
            extraContext = mapOf("messages" to msgs)
        )
        assertEquals("<|im_start|>user\n<|im_end|>\n<|im_start|>assistant\n", out)
    }

    // ---- N3: string-only embedded templates --------------------------------

    @Test
    fun `old HF qwen3 template is flagged as string-only, parts-aware ones are not`() {
        val hfQwen3 = "{%- for message in messages %}{%- if message.content is string %}" +
            "{%- set content = message.content %}{%- else %}{%- set content = '' %}{%- endif %}" +
            "{{- '<|im_start|>' + message.role + '\\n' + content + '<|im_end|>\\n' }}{%- endfor %}"
        assertTrue(ChatTemplateCompat.looksStringOnlyContent(hfQwen3))
        assertTrue(ChatTemplateCompat.looksStringOnlyContent(ChatTemplates.qwen))
        val litertQwen = "{%- for item in message['content'] -%}{%- if item['type'] == 'text' -%}" +
            "{{- item['text'] -}}{%- endif -%}{%- endfor -%}"
        assertTrue(!ChatTemplateCompat.looksStringOnlyContent(litertQwen))
        assertTrue(!ChatTemplateCompat.looksStringOnlyContent(ChatTemplateCompat.partsAware(ChatTemplates.qwen3)))
        assertTrue(!ChatTemplateCompat.looksStringOnlyContent(null))
    }
}
