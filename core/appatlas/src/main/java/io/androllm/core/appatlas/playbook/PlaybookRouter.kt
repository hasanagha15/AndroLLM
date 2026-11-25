package io.androllm.core.appatlas.playbook

/** A deterministic utterance → playbook match with extracted slot values (memory only). */
data class RouteMatch(val playbook: Playbook, val slots: SlotValues, val trigger: String)

/**
 * Branch N: zero-LLM fast path. Matches a normalized utterance against the
 * playbooks' trigger phrases (ru / en / translit, shipped in the bundle) with
 * `{slot}` capture by simple patterns:
 *
 * "найди {query} в ютубе"  ←  "Найди котиков в ютубе!"  →  youtube/search(query="котиков")
 *
 * Pure function. Rules:
 * - the WHOLE utterance must match one trigger (anchored), punctuation and
 *   politeness words ("пожалуйста", "please", "zəhmət olmasa") are ignored;
 * - literal words compare case-insensitively (ё = е); slot text keeps the
 *   user's original casing;
 * - captured slots go through [PlaybookSlots.fill] (type, length, one line);
 * - more literal words win; a tie between DIFFERENT playbooks is ambiguous →
 *   null, and the request goes to the model as usual.
 */
object PlaybookRouter {

    private val POLITE = listOf(
        "пожалуйста", "плиз", "please", "pls", "zəhmət olmasa", "zehmet olmasa", "pozhaluysta", "pojaluysta"
    )
    private val PUNCT = Regex("[\\p{P}\\p{S}&&[^{}_]]+")
    private val SPACES = Regex("\\s+")
    private val SLOT_RE = Regex("\\{([a-z][a-z0-9_]{0,23})\\}")

    /** Lower-cases (ё→е), drops punctuation and politeness words, collapses spaces. Case kept when [keepCase]. */
    fun normalize(s: String, keepCase: Boolean = false): String {
        var t = s.replace('ё', 'е').replace('Ё', 'Е')
        t = PUNCT.replace(t, " ")
        t = SPACES.replace(t, " ").trim()
        for (p in POLITE) {
            t = Regex("(?iu)(^|\\s)${Regex.escape(p)}(?=\\s|$)").replace(t, " ")
        }
        t = SPACES.replace(t, " ").trim()
        return if (keepCase) t else t.lowercase()
    }

    /** Literal (non-slot) words of a trigger. */
    fun literalTokens(trigger: String): List<String> =
        normalize(SLOT_RE.replace(trigger, " ")).split(' ').filter { it.isNotEmpty() }

    fun match(utterance: String, playbooks: List<Playbook>): RouteMatch? {
        val u = normalize(utterance, keepCase = true)
        if (u.isEmpty() || u.length > 300) return null
        data class Cand(val m: RouteMatch, val literals: Int)
        val cands = mutableListOf<Cand>()
        for (p in playbooks) {
            for (t in p.triggers) {
                val m = matchTrigger(u, t, p) ?: continue
                cands += Cand(m, literalTokens(t).size)
            }
        }
        if (cands.isEmpty()) return null
        val best = cands.maxOf { it.literals }
        val top = cands.filter { it.literals == best }
        if (top.map { it.m.playbook.id }.distinct().size > 1) return null
        return top.first().m
    }

    private fun matchTrigger(u: String, trigger: String, p: Playbook): RouteMatch? {
        val regex = compile(trigger) ?: return null
        val m = regex.matchEntire(u) ?: return null
        val names = SLOT_RE.findAll(trigger).map { it.groupValues[1] }.toList()
        val args = HashMap<String, String?>()
        names.forEachIndexed { i, n ->
            val v = m.groupValues.getOrNull(i + 1)?.trim().orEmpty()
            if (v.isEmpty()) return null
            if (args.containsKey(n) && args[n] != v) return null
            args[n] = v
        }
        return when (val f = PlaybookSlots.fill(p, args)) {
            is SlotFill.Ok -> RouteMatch(p, f.slots, trigger)
            is SlotFill.Error -> null
        }
    }

    /** Trigger → anchored, case-insensitive regex; literals normalized, slots lazy groups. */
    private fun compile(trigger: String): Regex? {
        val parts = mutableListOf<String>()
        var last = 0
        for (m in SLOT_RE.findAll(trigger)) {
            parts += literalPattern(trigger.substring(last, m.range.first))
            parts += "\u0000SLOT"
            last = m.range.last + 1
        }
        parts += literalPattern(trigger.substring(last))
        val sb = StringBuilder("^")
        var first = true
        for (part in parts) {
            if (part.isEmpty()) continue
            if (!first) sb.append("\\s*")
            sb.append(if (part == "\u0000SLOT") "(.+?)" else part)
            first = false
        }
        sb.append("$")
        return runCatching { Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
    }

    private fun literalPattern(raw: String): String {
        val words = normalize(raw).split(' ').filter { it.isNotEmpty() }
        return words.joinToString("\\s+") { w -> w.map { c -> if (c == 'е') "[её]" else Regex.escape(c.toString()) }.joinToString("") }
    }
}
