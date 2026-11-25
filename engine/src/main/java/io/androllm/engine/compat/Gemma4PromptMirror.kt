package io.androllm.engine.compat

/**
 * Branch P: Kotlin mirror of the chat template embedded in Google's Gemma 4
 * `.litertlm` containers, for TEXT-ONLY messages, no tool declarations and
 * thinking off — i.e. what the runtime renders for an ordinary chat turn
 * (LiteRT-LM binds `bos_token` to "", so no BOS text):
 *
 * ```
 * <|turn>system\n{system}<turn|>\n
 * <|turn>user\n{user}<turn|>\n
 * <|turn>model\n{reply}<turn|>\n
 * <|turn>model\n            ← generation prompt
 * ```
 *
 * The container template itself uses macros the app's renderer does not
 * implement; this mirror exists for the debug prompt log and the golden test.
 * Used only for display — the runtime always renders the real template.
 */
object Gemma4PromptMirror {

    fun render(messages: List<Pair<String, String>>, addGenerationPrompt: Boolean): String {
        val sb = StringBuilder()
        var rest = messages
        val first = messages.firstOrNull()
        if (first != null && (first.first == "system" || first.first == "developer")) {
            sb.append("<|turn>system\n").append(first.second.trim()).append("<turn|>\n")
            rest = messages.drop(1)
        }
        for ((role, content) in rest) {
            val r = if (role == "assistant") "model" else role
            val text = if (r == "model") stripThinking(content) else content.trim()
            sb.append("<|turn>").append(r).append('\n').append(text).append("<turn|>\n")
        }
        if (addGenerationPrompt) sb.append("<|turn>model\n")
        return sb.toString()
    }

    /** The template's `strip_thinking` macro: drops `<|channel>…<channel|>` blocks. */
    private fun stripThinking(text: String): String {
        val out = StringBuilder()
        for (part in text.split("<channel|>")) {
            out.append(if ("<|channel>" in part) part.substringBefore("<|channel>") else part)
        }
        return out.toString().trim()
    }
}
