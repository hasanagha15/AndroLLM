package io.androllm.engine.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch P: golden rendered prompts per family, plus the template decision
 * against the REAL templates embedded in the phone's containers
 * (test resources `templates/`: Qwen3-0.6B Mixed Int4 — the branch F
 * repack embeds the identical file — and Gemma 4 E2B IT, both read from the
 * `.litertlm` LlmMetadata on 25.11.2026).
 *
 * LiteRT-LM 0.19.1 renders with `bos_token = ""` (the session adds the start
 * token) and message content as a list of parts; thinking is always off.
 * The Gemma 4 golden strings were produced by rendering the real container
 * template with Jinja2 and list-of-parts messages.
 */
class ChatPromptGoldenTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("templates/$name")) { "missing $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val qwen3Container by lazy { resource("qwen3_0_6b_container.jinja") }
    private val gemma4Container by lazy { resource("gemma4_e2b_container.jinja") }

    private fun render(template: String, vararg msgs: Pair<String, String>): String =
        ChatTemplateRenderer().render(
            template = template,
            messages = msgs.map { ChatTemplateRenderer.RenderMessage(it.first, it.second) },
            bosToken = "",
            eosToken = "<|im_end|>",
            addGenerationPrompt = true,
            extraContext = mapOf("enable_thinking" to false)
        )

    // ── Qwen3 (hybrid: Qwen3-0.6B catalog + branch F container) ────────────

    @Test
    fun `qwen3 container template is string-only so the official template overrides it`() {
        assertFalse(TemplateSelector.isPartsAware(qwen3Container))
        val sel = TemplateSelector.select(ModelFamily.QWEN3, qwen3Container, "Qwen3 0.6B Mixed Int4")
        assertEquals(TemplateSelector.Source.FAMILY_OVERRIDE, sel.source)
        assertNotNull(sel.override)
        assertTrue("override must flatten list-of-parts content", TemplateSelector.isPartsAware(sel.override))
    }

    @Test
    fun `qwen3 golden prompt ends with the assistant header and an empty think block`() {
        val sel = TemplateSelector.select(ModelFamily.QWEN3, qwen3Container, "qwen3_0.6b_f_base_wi4b32")
        val rendered = render(sel.effective, "system" to "SYS", "user" to "privet, kak dela")
        // Identical to the official HF template rendered with string content.
        assertEquals(
            "<|im_start|>system\nSYS<|im_end|>\n" +
                "<|im_start|>user\nprivet, kak dela<|im_end|>\n" +
                "<|im_start|>assistant\n<think>\n\n</think>\n\n",
            rendered
        )
        val shape = PromptShapeCheck.check(rendered)
        assertTrue(shape.describe(), shape.ok)
    }

    @Test
    fun `qwen3 multi turn golden keeps history and ends with the assistant header`() {
        val sel = TemplateSelector.select(ModelFamily.QWEN3, qwen3Container, "Qwen3 0.6B")
        val rendered = render(
            sel.effective,
            "system" to "SYS", "user" to "Q1", "assistant" to "A1", "user" to "Q2"
        )
        assertEquals(
            "<|im_start|>system\nSYS<|im_end|>\n<|im_start|>user\nQ1<|im_end|>\n" +
                "<|im_start|>assistant\nA1<|im_end|>\n<|im_start|>user\nQ2<|im_end|>\n" +
                "<|im_start|>assistant\n<think>\n\n</think>\n\n",
            rendered
        )
    }

    // ── Qwen3-4B-Instruct-2507 (branch F target): no thinking at all ───────

    @Test
    fun `qwen3 instruct 2507 golden has no think block`() {
        val sel = TemplateSelector.select(ModelFamily.QWEN3, null, "Qwen3-4B-Instruct-2507 Mixed Int4")
        val rendered = render(sel.effective, "system" to "SYS", "user" to "privet, kak dela")
        assertEquals(
            "<|im_start|>system\nSYS<|im_end|>\n" +
                "<|im_start|>user\nprivet, kak dela<|im_end|>\n" +
                "<|im_start|>assistant\n",
            rendered
        )
        assertFalse(rendered.contains("<think>"))
        assertTrue(PromptShapeCheck.check(rendered).ok)
    }

    @Test
    fun `an embedded qwen template without a thinking switch is treated as instruct 2507`() {
        val embedded2507 = "{%- for message in messages %}{{- '<|im_start|>' + message.role + '\\n' + " +
            "message.content + '<|im_end|>\\n' }}{%- endfor %}" +
            "{%- if add_generation_prompt %}{{- '<|im_start|>assistant\\n' }}{%- endif %}"
        assertTrue(TemplateSelector.isQwen3Instruct2507("qwen3_4b_2507_f-4b-v1", null))
        assertTrue(TemplateSelector.isQwen3Instruct2507("custom", embedded2507))
        assertFalse(TemplateSelector.isQwen3Instruct2507("Qwen3 0.6B", qwen3Container))
    }

    // ── Gemma 4: the container's own template, never the Gemma 3 one ───────

    @Test
    fun `gemma4 container template is parts-aware so it is used as-is`() {
        assertTrue(TemplateSelector.isPartsAware(gemma4Container))
        val sel = TemplateSelector.select(ModelFamily.GEMMA, gemma4Container, "Gemma 4 E2B IT LiteRT")
        assertEquals(TemplateSelector.Source.CONTAINER, sel.source)
        assertNull("no override: LiteRT renders the container template", sel.override)
        assertFalse(sel.effective.contains("<start_of_turn>"))
        assertTrue(ToolCallSyntax.usesNativeToolApi(ModelFamily.GEMMA, sel.effective))
        assertEquals(ToolCallSyntax.GEMMA4_INSTRUCTION, ToolCallSyntax.instructionFor(ModelFamily.GEMMA, sel.effective))
    }

    @Test
    fun `gemma4 golden prompt uses turn tokens and ends with the model header`() {
        val rendered = Gemma4PromptMirror.render(
            listOf("system" to "SYS", "user" to "privet, kak dela"),
            addGenerationPrompt = true
        )
        assertEquals(
            "<|turn>system\nSYS<turn|>\n<|turn>user\nprivet, kak dela<turn|>\n<|turn>model\n",
            rendered
        )
        val shape = PromptShapeCheck.check(rendered)
        assertTrue(shape.describe(), shape.ok)
    }

    @Test
    fun `gemma4 multi turn golden maps assistant to model`() {
        val rendered = Gemma4PromptMirror.render(
            listOf("system" to "SYS", "user" to "privet, kak dela", "assistant" to "Привет!", "user" to "ok"),
            addGenerationPrompt = true
        )
        assertEquals(
            "<|turn>system\nSYS<turn|>\n<|turn>user\nprivet, kak dela<turn|>\n" +
                "<|turn>model\nПривет!<turn|>\n<|turn>user\nok<turn|>\n<|turn>model\n",
            rendered
        )
    }

    @Test
    fun `gemma3 containers without an embedded template keep the lenient gemma template`() {
        val sel = TemplateSelector.select(ModelFamily.GEMMA, null, "Gemma 3 1B")
        assertEquals(TemplateSelector.Source.FAMILY_OVERRIDE, sel.source)
        assertTrue(sel.effective.contains("<start_of_turn>"))
        assertNull(ToolCallSyntax.instructionFor(ModelFamily.GEMMA, sel.effective))
    }

    // ── Shape checks ───────────────────────────────────────────────────────

    @Test
    fun `shape check flags a prompt that does not end with the assistant header`() {
        val shape = PromptShapeCheck.check("<|im_start|>user\nhi<|im_end|>\n")
        assertFalse(shape.endsWithAssistantHeader)
        assertFalse(shape.ok)
    }

    @Test
    fun `shape check flags stray eos and duplicated bos`() {
        assertEquals("<eos>", PromptShapeCheck.check("<|turn>user\nhi<eos><turn|>\n<|turn>model\n").strayEos)
        assertEquals("<bos>", PromptShapeCheck.check("<bos><|turn>user\nhi<turn|>\n<|turn>model\n").duplicateBos)
    }

    @Test
    fun `excerpt is length capped and keeps both ends`() {
        val long = "HEAD" + "x".repeat(5000) + "<|im_start|>assistant\n"
        val ex = PromptShapeCheck.excerpt(long, headChars = 10, tailChars = 30)
        assertTrue(ex.startsWith("HEAD"))
        assertTrue(ex.endsWith("<|im_start|>assistant\n"))
        assertTrue(ex.length < 200)
    }

    @Test
    fun `qwen family gets the official tool_call instruction`() {
        val instruction = ToolCallSyntax.instructionFor(ModelFamily.QWEN3, ChatTemplates.qwen3)
        assertNotNull(instruction)
        assertTrue(instruction!!.contains("<tool_call>"))
        assertTrue(instruction.contains("</tool_call>"))
        assertFalse(ToolCallSyntax.usesNativeToolApi(ModelFamily.QWEN3, ChatTemplates.qwen3))
    }
}
