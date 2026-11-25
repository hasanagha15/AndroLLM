package io.androllm.engine.compat

/**
 * Branch P: decides which chat template a loaded model renders with.
 *
 * LiteRT-LM (>= 0.18, here 0.19.1) renders the prompt itself: every
 * `Conversation.sendMessage` applies a Jinja template (minijinja) to the
 * message list, where each message's `content` is a LIST of parts
 * (`[{"type":"text","text":"…"}]`, `ModelDataProcessor::MessageToTemplateInput`
 * → `NormalizeMessageContent`), `bos_token` is bound to "" (the session adds
 * the start token itself) and `enable_thinking` comes from the
 * `ThinkingConfig`. `ConversationConfig.chatTemplate == null` means "use the
 * template embedded in the .litertlm container".
 *
 * Rules (first match wins):
 *  1. The container embeds a template that reads the `text` parts
 *     (parts-aware, e.g. Gemma 4's `<|turn>…<turn|>` template) → use the
 *     container's own template, NO override. Overriding it was the step-4
 *     bug: Gemma 4 got the Gemma 3 `<start_of_turn>` template and read the
 *     system prompt back for 82 s.
 *  2. GENERIC family → the embedded template as-is (identity), else the
 *     generic fallback.
 *  3. Otherwise (no embedded template, or one written for string content
 *     only — the HF Qwen3 template does `messages[0].content + '\n\n'` and
 *     `content = ''` for non-strings, so on 0.19 it fails on a system message
 *     and renders an EMPTY user turn) → the family's official template made
 *     parts-aware ([ChatTemplateCompat.partsAware]). Qwen3 Instruct-2507
 *     gets [ChatTemplates.qwen3Instruct] (no thinking block).
 */
object TemplateSelector {

    enum class Source(val label: String) {
        CONTAINER("container (embedded, parts-aware)"),
        GENERIC_CONTAINER("container (generic mode)"),
        FAMILY_OVERRIDE("official family template (container template is string-only or missing)")
    }

    data class Selection(
        /** Template to pass as `ConversationConfig.chatTemplate`; null = container's own. */
        val override: String?,
        /** The template that is actually rendered (for diagnostics / the debug mirror). */
        val effective: String,
        val source: Source
    )

    private val TEXT_PART = Regex("""['"]text['"]""")

    /** True when [template] flattens list-of-parts content (reads the `text` parts). */
    fun isPartsAware(template: String?): Boolean =
        !template.isNullOrBlank() && TEXT_PART.containsMatchIn(template)

    /**
     * True for the Qwen3 Instruct-2507 line (no thinking mode). Detected from
     * the model name ("2507") or from an embedded Qwen3 template without any
     * thinking switch (the 2507 Instruct template has neither `<think>` nor
     * `enable_thinking`).
     */
    fun isQwen3Instruct2507(modelName: String?, embedded: String?): Boolean {
        val n = modelName.orEmpty().lowercase()
        if ("2507" in n && "thinking" !in n) return true
        val t = embedded ?: return false
        return t.contains("<|im_start|>") && !t.contains("enable_thinking") && !t.contains("<think>")
    }

    fun select(family: ModelFamily, embedded: String?, modelName: String?): Selection {
        if (isPartsAware(embedded)) {
            return Selection(override = null, effective = embedded!!, source = Source.CONTAINER)
        }
        if (family == ModelFamily.GENERIC) {
            return if (embedded.isNullOrBlank()) {
                val t = ChatTemplateCompat.partsAware(ChatTemplates.generic)
                Selection(t, t, Source.FAMILY_OVERRIDE)
            } else {
                Selection(override = null, effective = embedded, source = Source.GENERIC_CONTAINER)
            }
        }
        val base = if (family == ModelFamily.QWEN3 && isQwen3Instruct2507(modelName, embedded)) {
            ChatTemplates.qwen3Instruct
        } else {
            ModelFamilyRegistry.configFor(family).chatTemplate
        }
        val t = ChatTemplateCompat.partsAware(base)
        return Selection(override = t, effective = t, source = Source.FAMILY_OVERRIDE)
    }
}
