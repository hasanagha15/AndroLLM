package io.androllm.core.appatlas.playbook

import io.androllm.core.accessibility.planner.SafetyClassifier
import io.androllm.core.appatlas.index.SchemePolicy
import io.androllm.core.appatlas.index.SensitiveClassifier
import kotlinx.serialization.json.Json

/** Outcome of validating a bundle. Rejections name the playbook (id or #index) and a short reason. */
data class ImportReport(
    val accepted: List<Playbook>,
    val rejected: List<Rejection>
) {
    data class Rejection(val ref: String, val reason: String)

    fun summary(): String = buildString {
        append("imported ").append(accepted.size).append(", rejected ").append(rejected.size)
        if (rejected.isNotEmpty()) {
            append(": ")
            append(rejected.take(10).joinToString("; ") { "${it.ref.take(80)}: ${it.reason.take(120)}" })
            if (rejected.size > 10) append("; …")
        }
    }
}

/**
 * Strict, pure validation of playbook bundles (file / content URI / Jarvis
 * link). Anything unexpected is rejected — unknown fields, unknown ops,
 * oversized values, deep links to blocked schemes, and EVERY playbook for a
 * protected package (SensitiveAppGuard / SensitiveAppStore extras / the
 * banking-payment heuristic): those are never imported.
 *
 * Risk is recomputed here: a step whose target looks like send / pay /
 * confirm / delete / call / post / grant (SafetyClassifier, the same
 * vocabulary as branch M's RiskyElementGate) is marked risky and the playbook
 * becomes "confirm" (tap-only card at that step), whatever the bundle says.
 */
object PlaybookValidator {

    const val MAX_BUNDLE_CHARS = 512 * 1024
    const val MAX_PLAYBOOKS = 200
    const val MAX_STEPS = 30
    const val MAX_PARAMS = 6
    const val MAX_LOCATORS = 6
    const val MAX_TRIGGERS = 12
    const val MAX_EXPECT_ITEMS = 8
    const val MAX_DESCRIPTION = 120
    const val MAX_UI_STRING = 80
    const val MAX_TEXT_TEMPLATE = 200
    const val MAX_URI = 500
    const val MIN_TIMEOUT_MS = 100L
    const val MAX_TIMEOUT_MS = 30_000L
    const val MAX_WAIT_MS = 10_000L

    val OPS = setOf("tap", "type", "scroll", "wait", "back", "open_deeplink")
    private val DIRECTIONS = setOf("up", "down", "left", "right")
    private val PARAM_TYPES = setOf(PlaybookParam.TYPE_TEXT, PlaybookParam.TYPE_INT, PlaybookParam.TYPE_ENUM)

    private val PKG_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val NAME_RE = Regex("^[a-z][a-z0-9_]{0,39}$")
    private val PARAM_RE = Regex("^[a-z][a-z0-9_]{0,23}$")
    private val ID_ENTRY_RE = Regex("^[A-Za-z0-9_]{1,60}$")
    private val CLASS_RE = Regex("^[A-Za-z_][A-Za-z0-9_.$]{0,120}$")
    private val SLOT_RE = Regex("\\{([^{}]*)\\}")
    private val BAD_CHARS = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029<>`\\[\\]]")

    /** Strict decoder: an unknown field anywhere rejects the bundle. */
    private val strictJson = Json { ignoreUnknownKeys = false; isLenient = false; explicitNulls = false }

    /**
     * Parses and validates [raw]. [extras] = the owner's protected packages
     * (SensitiveAppStore); [labelOf] = installed app label from the atlas, if known.
     */
    fun parseBundle(
        raw: String,
        extras: Set<String>,
        ownPackage: String? = null,
        labelOf: (String) -> String? = { null }
    ): ImportReport {
        if (raw.length > MAX_BUNDLE_CHARS) return bundleError("bundle larger than $MAX_BUNDLE_CHARS chars")
        val bundle = try {
            strictJson.decodeFromString(PlaybookBundle.serializer(), raw)
        } catch (e: Exception) {
            return bundleError("not a valid playbook bundle (${e.javaClass.simpleName})")
        }
        return validateBundle(bundle, extras, ownPackage, labelOf)
    }

    fun validateBundle(
        bundle: PlaybookBundle,
        extras: Set<String>,
        ownPackage: String? = null,
        labelOf: (String) -> String? = { null }
    ): ImportReport {
        if (bundle.format != PlaybookBundle.FORMAT) return bundleError("format must be '${PlaybookBundle.FORMAT}'")
        if (bundle.schemaVersion != PlaybookBundle.SCHEMA_VERSION) {
            return bundleError("unsupported schemaVersion ${bundle.schemaVersion} (expected ${PlaybookBundle.SCHEMA_VERSION})")
        }
        if (bundle.playbooks.size > MAX_PLAYBOOKS) return bundleError("more than $MAX_PLAYBOOKS playbooks")
        val accepted = mutableListOf<Playbook>()
        val rejected = mutableListOf<ImportReport.Rejection>()
        val seen = HashSet<String>()
        bundle.playbooks.forEachIndexed { i, p ->
            val ref = p.id.takeIf { it.length in 1..120 && !BAD_CHARS.containsMatchIn(it) } ?: "#$i"
            val error = validate(p, extras, ownPackage, labelOf) ?: if (!seen.add(p.id)) "duplicate id" else null
            if (error != null) rejected += ImportReport.Rejection(ref, error)
            else accepted += withComputedRisk(p)
        }
        return ImportReport(accepted, rejected)
    }

    /** Null when [p] is valid, else the first problem found. */
    fun validate(
        p: Playbook,
        extras: Set<String>,
        ownPackage: String? = null,
        labelOf: (String) -> String? = { null }
    ): String? {
        if (!PKG_RE.matches(p.packageName) || p.packageName.length > 120) return "malformed package"
        if (ownPackage != null && p.packageName == ownPackage) return "playbooks for this assistant itself are not allowed"
        // Protected apps are never imported (guard list, owner's extras, banking/payment heuristic).
        val label = listOfNotNull(p.appLabel, labelOf(p.packageName)).joinToString(" ")
        SensitiveClassifier.reason(p.packageName, label, extras)?.let { return "protected app: $it" }
        if (!NAME_RE.matches(p.name)) return "malformed name"
        if (p.id != "${p.packageName}/${p.name}") return "id must be '<package>/<name>'"
        p.appLabel?.let { if (it.length > MAX_UI_STRING || BAD_CHARS.containsMatchIn(it)) return "bad appLabel" }
        if (p.description.isBlank() || p.description.length > MAX_DESCRIPTION || BAD_CHARS.containsMatchIn(p.description) ||
            p.description.any { it == '{' || it == '}' }
        ) return "description must be 1..$MAX_DESCRIPTION plain chars"
        if (p.versionMin < 0 || p.versionMax < p.versionMin) return "bad versionCode range"
        if (p.risk != Playbook.RISK_NONE && p.risk != Playbook.RISK_CONFIRM) return "risk must be none|confirm"
        if (p.learnedAt < 0) return "bad learnedAt"

        if (p.params.size > MAX_PARAMS) return "more than $MAX_PARAMS params"
        val paramNames = HashSet<String>()
        for (param in p.params) {
            if (!PARAM_RE.matches(param.name)) return "malformed param name"
            if (!paramNames.add(param.name)) return "duplicate param '${param.name}'"
            if (param.type !in PARAM_TYPES) return "unknown param type '${param.type.take(20)}'"
            if (param.maxLength !in 1..500) return "param maxLength must be 1..500"
            if (param.description.length > MAX_DESCRIPTION || BAD_CHARS.containsMatchIn(param.description)) return "bad param description"
            if (param.type == PlaybookParam.TYPE_ENUM) {
                if (param.values.isEmpty() || param.values.size > 20) return "enum param needs 1..20 values"
                if (param.values.any { it.isBlank() || it.length > 40 || BAD_CHARS.containsMatchIn(it) }) return "bad enum value"
            } else if (param.values.isNotEmpty()) return "values only allowed for enum params"
        }

        if (p.triggers.size > MAX_TRIGGERS) return "more than $MAX_TRIGGERS triggers"
        for (t in p.triggers) {
            if (t.isBlank() || t.length > 80 || BAD_CHARS.containsMatchIn(t)) return "bad trigger"
            slotsOf(t)?.let { slots -> if (slots.any { it !in paramNames }) return "trigger uses an unknown slot" } ?: return "bad trigger placeholder"
            if (PlaybookRouter.literalTokens(t).isEmpty()) return "trigger needs at least one literal word"
        }

        if (p.steps.isEmpty() || p.steps.size > MAX_STEPS) return "steps must be 1..$MAX_STEPS"
        for ((i, s) in p.steps.withIndex()) {
            validateStep(s, paramNames)?.let { return "step $i: $it" }
        }
        p.postcondition?.let { validateExpect(it)?.let { e -> return "postcondition: $e" } }
        return null
    }

    private fun validateStep(s: PlaybookStep, params: Set<String>): String? {
        if (s.op !in OPS) return "unknown op '${s.op.take(20)}'"
        if (s.timeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) return "timeoutMs must be $MIN_TIMEOUT_MS..$MAX_TIMEOUT_MS"
        s.minScore?.let { if (it < 0.1 || it > 5.0) return "minScore must be 0.1..5" }
        s.expect?.let { validateExpect(it)?.let { e -> return "expect: $e" } }
        if (s.locators.size > MAX_LOCATORS) return "more than $MAX_LOCATORS locators"
        for (l in s.locators) validateLocator(l)?.let { return "locator: $it" }
        // Fields that do not belong to the op are rejected (no hidden payloads).
        if (s.op != "type" && s.text != null) return "text only allowed for type"
        if (s.op != "open_deeplink" && s.uri != null) return "uri only allowed for open_deeplink"
        if (s.op != "scroll" && s.direction != null) return "direction only allowed for scroll"
        if (s.op != "wait" && s.waitMs != null) return "waitMs only allowed for wait"
        if (s.op !in setOf("tap", "type") && s.locators.isNotEmpty()) return "locators only allowed for tap/type"
        when (s.op) {
            "tap" -> if (s.locators.isEmpty()) return "tap needs locators"
            "type" -> {
                val t = s.text ?: return "type needs text"
                if (t.isEmpty() || t.length > MAX_TEXT_TEMPLATE) return "text must be 1..$MAX_TEXT_TEMPLATE chars"
                if (t.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) return "text must be one plain line"
                val slots = slotsOf(t) ?: return "bad placeholder in text"
                if (slots.any { it !in params }) return "text uses an unknown slot"
            }
            "scroll" -> if (s.direction !in DIRECTIONS) return "direction must be up|down|left|right"
            "wait" -> { val w = s.waitMs ?: return "wait needs waitMs"; if (w !in 0..MAX_WAIT_MS) return "waitMs must be 0..$MAX_WAIT_MS" }
            "back" -> Unit
            "open_deeplink" -> return validateUriTemplate(s.uri ?: return "open_deeplink needs uri", params)
        }
        return null
    }

    private fun validateLocator(l: Locator): String? {
        if (l.kind !in Locator.KINDS) return "unknown kind '${l.kind.take(20)}'"
        l.weight?.let { if (it <= 0.0 || it > 2.0) return "weight must be (0, 2]" }
        val v = l.value
        when (l.kind) {
            Locator.ID -> if (v == null || !ID_ENTRY_RE.matches(v) || l.index != null || l.bounds != null) return "id needs an entry name only"
            Locator.DESC, Locator.TEXT -> if (v.isNullOrBlank() || v.length > MAX_UI_STRING || BAD_CHARS.containsMatchIn(v) || l.index != null || l.bounds != null) return "${l.kind} needs a plain string ≤ $MAX_UI_STRING"
            Locator.CLASS_INDEX -> if (v == null || !CLASS_RE.matches(v) || l.index == null || l.index !in 0..200 || l.bounds != null) return "class_index needs class + index 0..200"
            Locator.BOUNDS -> {
                val b = l.bounds ?: return "bounds needs 4 numbers"
                if (v != null || l.index != null || b.size != 4 || b.any { it < 0.0 || it > 1.0 } || b[0] >= b[2] || b[1] >= b[3]) return "bounds must be [l,t,r,b] in 0..1"
            }
        }
        return null
    }

    private fun validateExpect(e: Expect): String? {
        if (e.isEmpty) return "empty"
        if (e.match != "all" && e.match != "any") return "match must be all|any"
        if (e.ids.size > MAX_EXPECT_ITEMS || e.texts.size > MAX_EXPECT_ITEMS) return "more than $MAX_EXPECT_ITEMS items"
        if (e.ids.any { !ID_ENTRY_RE.matches(it) }) return "bad id"
        if (e.texts.any { it.isBlank() || it.length > MAX_UI_STRING || BAD_CHARS.containsMatchIn(it) }) return "bad text"
        return null
    }

    /** Scheme and authority fixed; slots only after the host; blocked / compose links refused. */
    private fun validateUriTemplate(uri: String, params: Set<String>): String? {
        if (uri.isEmpty() || uri.length > MAX_URI) return "uri must be 1..$MAX_URI chars"
        if (uri.any { it.isWhitespace() || Character.isISOControl(it) || it in "<>\"`" }) return "uri has forbidden characters"
        val slots = slotsOf(uri) ?: return "bad placeholder in uri"
        if (slots.any { it !in params }) return "uri uses an unknown slot"
        val scheme = SchemePolicy.schemeOf(uri) ?: return "uri needs a scheme"
        if (SchemePolicy.isBlockedScheme(scheme)) return "uri scheme '$scheme' is not allowed (calls / messages / payments / intents)"
        val firstSlot = uri.indexOf('{')
        val authorityEnd = authorityEnd(uri)
        if (firstSlot in 0 until authorityEnd) return "placeholders are not allowed in the scheme or host"
        val sample = SLOT_RE.replace(uri, "x")
        if (SchemePolicy.isComposeWebUrl(sample)) return "share / compose links are not allowed"
        return null
    }

    /** End index of "scheme://host" (or "scheme:" for opaque URIs). */
    internal fun authorityEnd(uri: String): Int {
        val colon = uri.indexOf(':')
        if (colon < 0) return 0
        if (!uri.startsWith("//", colon + 1)) return colon + 1
        val start = colon + 3
        val end = uri.indexOfAny(charArrayOf('/', '?', '#'), start)
        return if (end < 0) uri.length else end
    }

    /** Slot names in [template], or null when braces are malformed / slot names invalid. */
    fun slotsOf(template: String): List<String>? {
        val names = SLOT_RE.findAll(template).map { it.groupValues[1] }.toList()
        if (names.any { !PARAM_RE.matches(it) }) return null
        val stripped = SLOT_RE.replace(template, "")
        if ('{' in stripped || '}' in stripped) return null
        return names
    }

    /** True when the step's target (by its locators) looks like send / pay / confirm / delete / call / post / grant. */
    fun isRiskyStep(s: PlaybookStep): Boolean {
        if (s.risky) return true
        if (s.op != "tap") return false
        return s.locators.any { l ->
            when (l.kind) {
                Locator.ID -> l.value?.let { SafetyClassifier.isRiskyTarget(it.replace('_', ' ')) } == true
                Locator.DESC, Locator.TEXT -> l.value?.let { SafetyClassifier.isRiskyTarget(it) } == true
                else -> false
            }
        }
    }

    /** Risk upgraded from the steps; risky steps flagged explicitly. */
    fun withComputedRisk(p: Playbook): Playbook {
        val steps = p.steps.map { if (!it.risky && isRiskyStep(it)) it.copy(risky = true) else it }
        val risky = p.risk == Playbook.RISK_CONFIRM || steps.any { it.risky }
        return p.copy(steps = steps, risk = if (risky) Playbook.RISK_CONFIRM else Playbook.RISK_NONE)
    }

    private fun bundleError(reason: String) = ImportReport(emptyList(), listOf(ImportReport.Rejection("bundle", reason)))
}
