package io.androllm.engine.compat

/**
 * Makes the app's family chat templates ([ChatTemplates]) work with both
 * LiteRT-LM message shapes:
 *
 * - LiteRT-LM <= 0.17 passed `message['content']` to the Jinja template as a
 *   plain string;
 * - LiteRT-LM >= 0.18 (upstream commit 6c6b4582, "normalize message content to
 *   multimodal parts") always passes a LIST of parts:
 *   `[{"type": "text", "text": "..."}, {"type": "image"}, ...]`
 *   (see models/README.md of LiteRT-LM, "Content Types").
 *
 * The official HF templates in [ChatTemplates] concatenate/trim
 * `message['content']` as a string, so on 0.18+ they either fail to render
 * ("Failed to apply template") or silently render an empty turn
 * (LiteRT-LM issue #3688). [partsAware] rewrites a template so every message
 * loop first flattens the content into `msgns.text` — the string itself when it
 * already is one (0.16 runtime, the app's own [ChatTemplateRenderer]), or the
 * concatenated `text` parts otherwise — and the body reads `msgns.text`.
 *
 * Only the app-defined templates are rewritten. The unmodified constants stay
 * in [ChatTemplates] because [ModelCompatibilityResolver] compares container
 * templates against them for family detection.
 */
object ChatTemplateCompat {

    private val MESSAGE_LOOP = Regex("""\{%-?\s*for message in messages\s*-?%\}""")

    private const val CONTENT_REF = "message['content']"

    /**
     * Flattens the current `message`'s content into `msgns.text` (no output).
     * A namespace is used instead of a plain `set` because assignments inside
     * `if`/`for` blocks are block-scoped in Jinja/minijinja; namespace
     * attribute writes are not.
     */
    const val FLATTEN_CONTENT: String =
        "{% set msgns = namespace(text='') %}" +
            "{% if message['content'] is string or message['content'] is none %}" +
            "{% set msgns.text = message['content'] %}" +
            "{% else %}" +
            "{% for part in message['content'] %}" +
            "{% if part['type'] == 'text' %}{% set msgns.text = msgns.text + part['text'] %}{% endif %}" +
            "{% endfor %}" +
            "{% endif %}"

    /** Marker so a template is never rewritten twice. */
    private const val MARKER = "{% set msgns = namespace("

    fun partsAware(template: String): String {
        if (template.contains(MARKER)) return template
        if (!template.contains(CONTENT_REF)) return template
        val body = template.replace(CONTENT_REF, "msgns.text")
        return MESSAGE_LOOP.replace(body) { m -> m.value + FLATTEN_CONTENT }
    }
}
