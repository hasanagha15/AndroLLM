package io.androllm.engine.compat

/**
 * Branch P: sanity checks on a rendered chat prompt, used by the debug prompt
 * log and the golden tests. A prompt the model can answer must
 *  - END with the assistant-turn header (otherwise the model continues the
 *    user's text — the "echo" symptom),
 *  - contain no end-of-sequence token (a stray EOS ends the sequence),
 *  - contain no BOS text: LiteRT-LM binds `bos_token` to "" and the session
 *    prepends the start token itself, so a literal BOS would be a duplicate.
 */
object PromptShapeCheck {

    data class Shape(
        val assistantHeader: String?,
        val endsWithAssistantHeader: Boolean,
        val strayEos: String?,
        val duplicateBos: String?
    ) {
        val ok: Boolean get() = endsWithAssistantHeader && strayEos == null && duplicateBos == null

        fun describe(): String =
            "endsWithAssistantHeader=$endsWithAssistantHeader" +
                (strayEos?.let { " strayEos='$it'" } ?: "") +
                (duplicateBos?.let { " duplicateBos='$it'" } ?: "")
    }

    /** Assistant-turn headers a prompt may legitimately end with, per template style. */
    private val ASSISTANT_HEADERS = listOf(
        "<|im_start|>assistant\n<think>\n\n</think>\n\n", // Qwen3 hybrid, thinking off
        "<|im_start|>assistant\n",                         // ChatML (Qwen, SmolLM)
        "<|turn>model\n",                                  // Gemma 4
        "<start_of_turn>model\n",                          // Gemma 3
        "<|start_header_id|>assistant<|end_header_id|>\n\n", // Llama 3
        "<|assistant|>\n",                                 // Phi / TinyLlama
        "<|Assistant|>",                                   // DeepSeek
        " [/INST]"                                         // Mistral
    )

    private val EOS_TOKENS = listOf("<|endoftext|>", "<eos>", "</s>", "<|eot_id|>", "<|end_of_text|>")
    private val BOS_TOKENS = listOf("<bos>", "<s>", "<|begin_of_text|>", "<｜begin▁of▁sentence｜>")

    fun check(rendered: String): Shape {
        val header = ASSISTANT_HEADERS.firstOrNull { rendered.endsWith(it) }
        return Shape(
            assistantHeader = header,
            endsWithAssistantHeader = header != null,
            strayEos = EOS_TOKENS.firstOrNull { rendered.contains(it) },
            duplicateBos = BOS_TOKENS.firstOrNull { rendered.contains(it) }
        )
    }

    /**
     * Length-capped excerpt for logcat: head + tail with the elided middle
     * counted, so the turn markers at both ends are always visible.
     */
    fun excerpt(rendered: String, headChars: Int = 400, tailChars: Int = 600): String =
        if (rendered.length <= headChars + tailChars) {
            rendered
        } else {
            rendered.take(headChars) + "\n…[${rendered.length - headChars - tailChars} chars elided]…\n" +
                rendered.takeLast(tailChars)
        }
}
