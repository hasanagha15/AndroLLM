package io.androllm.core.appatlas.playbook

import io.androllm.core.appatlas.index.SchemePolicy
import java.net.URLEncoder

/**
 * Slot values for ONE run. They exist only in memory for the duration of the
 * run: never stored, never logged ([toString] is redacted), never echoed in
 * a tool result or a relearn record.
 */
class SlotValues internal constructor(private val values: Map<String, String>) {
    operator fun get(name: String): String? = values[name]
    val names: Set<String> get() = values.keys
    override fun toString(): String = "SlotValues(${values.keys.joinToString { "$it=<redacted>" }})"
    override fun equals(other: Any?): Boolean = other is SlotValues && other.values == values
    override fun hashCode(): Int = values.hashCode()

    companion object {
        val EMPTY = SlotValues(emptyMap())
    }
}

sealed interface SlotFill {
    data class Ok(val slots: SlotValues) : SlotFill
    /** [reason] names the param, never echoes its value. */
    data class Error(val reason: String) : SlotFill
}

/** Pure slot filling / template rendering for playbook replay. */
object PlaybookSlots {

    private val SLOT_RE = Regex("\\{([a-z][a-z0-9_]{0,23})\\}")
    private val INT_RE = Regex("^-?\\d{1,18}$")

    /** Validates [args] against the playbook's params (required, type, length, one line). */
    fun fill(playbook: Playbook, args: Map<String, String?>): SlotFill {
        val out = LinkedHashMap<String, String>()
        for (p in playbook.params) {
            val raw = args[p.name]?.trim()
            if (raw.isNullOrEmpty()) {
                if (p.required) return SlotFill.Error("missing required argument '${p.name}'")
                continue
            }
            if (raw.length > p.maxLength) return SlotFill.Error("argument '${p.name}' is longer than ${p.maxLength} chars")
            // A line break typed into a field can submit (send) it: never allowed in a slot.
            if (raw.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() || it == ' ' || it == ' ' }) {
                return SlotFill.Error("argument '${p.name}' must be a single line of plain text")
            }
            val value = when (p.type) {
                PlaybookParam.TYPE_INT -> raw.takeIf { INT_RE.matches(it) }
                    ?: return SlotFill.Error("argument '${p.name}' must be an integer")
                PlaybookParam.TYPE_ENUM -> p.values.firstOrNull { it.equals(raw, ignoreCase = true) }
                    ?: return SlotFill.Error("argument '${p.name}' must be one of: ${p.values.joinToString(", ")}")
                else -> raw
            }
            out[p.name] = value
        }
        return SlotFill.Ok(SlotValues(out))
    }

    /** Text for a type step; null when a used slot has no value. */
    fun renderText(template: String, slots: SlotValues): String? {
        var missing = false
        val s = SLOT_RE.replace(template) { m -> slots[m.groupValues[1]] ?: run { missing = true; "" } }
        return if (missing) null else s
    }

    /**
     * URI for open_deeplink: slot values are URL-encoded, the scheme and
     * authority must stay exactly those of the template, and the result is
     * re-checked against the scheme denylist / compose links. Null = refused.
     */
    fun renderUri(template: String, slots: SlotValues): String? {
        var missing = false
        val s = SLOT_RE.replace(template) { m ->
            val v = slots[m.groupValues[1]] ?: run { missing = true; "" }
            URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        }
        if (missing) return null
        val authority = template.substring(0, PlaybookValidator.authorityEnd(template))
        if (!s.startsWith(authority)) return null
        if (s.length > PlaybookValidator.MAX_URI * 4) return null
        if (s.any { it.isWhitespace() || Character.isISOControl(it) }) return null
        if (SchemePolicy.isBlockedUri(s) || SchemePolicy.isComposeWebUrl(s)) return null
        return s
    }
}
