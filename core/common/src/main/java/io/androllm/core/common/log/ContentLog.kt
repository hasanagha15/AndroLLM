package io.androllm.core.common.log

/**
 * Branch Q: one switch for putting conversation CONTENT into logcat.
 *
 * Message text, model replies, STT transcripts, TTS sentences, tool
 * arguments/output, memory facts — none of it may reach logcat by default,
 * in debug or release. Log sites describe content by role and length only
 * ([text] returns "[N chars]"); the actual text is included only while
 * [enabled] is on, which mirrors `GenerationConfig.debugTokenLogging` (set
 * explicitly by the owner, default off). The engine checks its per-turn
 * `config.debugTokenLogging` directly; modules without a config use this.
 */
object ContentLog {

    @Volatile
    var enabled: Boolean = false

    /** "[N chars]" by default; the quoted, length-capped text only when [enabled]. */
    fun text(value: CharSequence?, max: Int = 80): String = text(value, max, enabled)

    /** Same as [text] but with an explicit switch (engine: `config.debugTokenLogging`). */
    fun text(value: CharSequence?, max: Int, include: Boolean): String = when {
        value == null -> "null"
        include -> "'${value.take(max)}'"
        else -> "[${value.length} chars]"
    }

    /** Validation errors can quote argument values: count only unless [enabled]. */
    fun errors(errors: Collection<Any?>?): String = when {
        errors == null -> "null"
        enabled -> errors.toString()
        else -> "[${errors.size} error(s)]"
    }
}
