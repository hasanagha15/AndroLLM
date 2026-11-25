package io.androllm.engine.compat

/**
 * Branch P: how each family emits a tool call, and how the app tells it.
 *
 * Before branch P the tool advertisement listed the tools but never said HOW
 * to call one, and [io.androllm.engine.core.NativeToolCallScanner] did not
 * know Qwen's `<tool_call>` tags — so `nativeCalls=0` on every local model.
 *
 *  - Qwen2 / 2.5 / 3: the official Qwen function-calling format (the HF
 *    template's tools block puts exactly this instruction into the system
 *    prompt):
 *    `<tool_call>\n{"name": "<tool>", "arguments": {…}}\n</tool_call>`.
 *  - Gemma 4: native function calling — the declarations go through the
 *    LiteRT-LM tool API (`ConversationConfig.tools`), the container template
 *    renders them as `<|tool>declaration:…<tool|>` and the model answers
 *    `<|tool_call>call:name{key:<|"|>value<|"|>}<tool_call|>`, which the
 *    runtime's Gemma4DataProcessor parses into `Message.toolCalls`.
 */
object ToolCallSyntax {

    const val QWEN_INSTRUCTION: String =
        "HOW TO CALL A TOOL: only when the request needs one of the tools listed below, reply with ONLY " +
            "the call, no other text, in exactly this format:\n" +
            "<tool_call>\n{\"name\": \"<tool name>\", \"arguments\": {<arguments as JSON>}}\n</tool_call>\n" +
            "Example: <tool_call>\n{\"name\": \"get_battery\", \"arguments\": {}}\n</tool_call>\n" +
            "Questions you can answer yourself (facts, chat, translation) get a plain-text answer without any tool. " +
            "After a tool result arrives, answer the user in plain text."

    // Branch P (phone run 1): an example with a placeholder name made Gemma 4
    // copy it literally ("call:tool_name{…}") — name no syntax here; the
    // runtime's declarations already teach the format.
    const val GEMMA4_INSTRUCTION: String =
        "HOW TO CALL A TOOL: only when the request needs one of the declared tools, call it with your " +
            "native function-calling format, using the exact declared tool name. Questions you can answer " +
            "yourself (facts, chat, translation) get a plain-text answer without any tool. " +
            "After a tool result arrives, answer the user in plain text."

    /** True when the container's template carries Gemma 4's native tool syntax. */
    fun isGemma4Template(template: String?): Boolean =
        template != null && template.contains("<|tool_call>") && template.contains("<|turn>")

    /** The instruction line for [family] (null = no known format). */
    fun instructionFor(family: ModelFamily, effectiveTemplate: String?): String? = when {
        family == ModelFamily.GEMMA && isGemma4Template(effectiveTemplate) -> GEMMA4_INSTRUCTION
        family == ModelFamily.QWEN2 || family == ModelFamily.QWEN2P5 || family == ModelFamily.QWEN3 -> QWEN_INSTRUCTION
        else -> null
    }

    /** True when tools should go through the runtime's native tool API. */
    fun usesNativeToolApi(family: ModelFamily, effectiveTemplate: String?): Boolean =
        family == ModelFamily.GEMMA && isGemma4Template(effectiveTemplate)
}
