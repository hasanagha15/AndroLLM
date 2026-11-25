package io.androllm.core.appatlas.playbook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Branch N — app playbooks. A playbook is a named, parameterized action of
 * one app (`youtube.search(query)`) learned OFFLINE by Claude/Jarvis (adb
 * crawl on a laptop) and replayed on the phone deterministically: no LLM call
 * per step. The local model only picks WHICH playbook to run (or the
 * zero-LLM [PlaybookRouter] does). JSON format: documentation/PLAYBOOKS_SCHEMA.md.
 */
@Serializable
data class PlaybookBundle(
    /** Always [FORMAT]. */
    val format: String,
    /** Format version; only [SCHEMA_VERSION] is accepted. */
    val schemaVersion: Int,
    val generatedAt: Long = 0L,
    val playbooks: List<Playbook>
) {
    companion object {
        const val FORMAT = "androllm.playbooks"
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class Playbook(
    /** "<package>/<name>" — stable id the model passes to playbook_run. */
    val id: String,
    @SerialName("package") val packageName: String,
    /** Action name, `[a-z][a-z0-9_]{0,39}` ("search", "new_note"). */
    val name: String,
    /** Optional app label as seen on the crawl device (also checked against the sensitive heuristic). */
    val appLabel: String? = null,
    /** One short line for the model (≤ 120 chars). */
    val description: String,
    val params: List<PlaybookParam> = emptyList(),
    /** App versionCode range the playbook was learned/verified on (inclusive). */
    val versionMin: Long,
    val versionMax: Long,
    /** Declared risk: "none" or "confirm". The importer upgrades it when a step target is risky. */
    val risk: String = RISK_NONE,
    /** Zero-LLM trigger phrases with {slot} placeholders (ru / en / translit). */
    val triggers: List<String> = emptyList(),
    val steps: List<PlaybookStep>,
    /** Cheap verifier: what must be visible after the last step (playbook_run → verified). */
    val postcondition: Expect? = null,
    val learnedAt: Long = 0L
) {
    val isRisky: Boolean get() = risk == RISK_CONFIRM

    companion object {
        const val RISK_NONE = "none"
        const val RISK_CONFIRM = "confirm"
    }
}

@Serializable
data class PlaybookParam(
    val name: String,
    /** "text" | "int" | "enum". */
    val type: String = TYPE_TEXT,
    val description: String = "",
    val required: Boolean = true,
    val maxLength: Int = 200,
    /** Allowed values for type = enum. */
    val values: List<String> = emptyList()
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_INT = "int"
        const val TYPE_ENUM = "enum"
    }
}

@Serializable
data class PlaybookStep(
    /** tap | type | scroll | wait | back | open_deeplink. */
    val op: String,
    /** Weighted locators of the target element (tap; type — empty = focused field). */
    val locators: List<Locator> = emptyList(),
    /** type: text template, may contain {slot} placeholders. */
    val text: String? = null,
    /** open_deeplink: URI template; {slot} only after the host (URL-encoded on fill). */
    val uri: String? = null,
    /** scroll: up | down | left | right. */
    val direction: String? = null,
    /** wait: fixed pause (ms) — prefer [expect]. */
    val waitMs: Long? = null,
    /** What must be visible BEFORE acting (waited for up to [timeoutMs]). */
    val expect: Expect? = null,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Declared risky (send/pay/confirm/delete/call/post/grant); also computed from the locators. */
    val risky: Boolean = false,
    /** Minimum locator score for this step (default [Locator.DEFAULT_MIN_SCORE]). */
    val minScore: Double? = null
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 4_000L
    }
}

/**
 * One way to recognise the target element; the resolver sums the weights of
 * matching locators per node and needs at least the step's minimum score.
 */
@Serializable
data class Locator(
    /** id | desc | text | class_index | bounds. */
    val kind: String,
    /** id: resource entry name; desc: content-desc; text: app UI string; class_index: class name. */
    val value: String? = null,
    /** class_index: index among visible nodes of that class. */
    val index: Int? = null,
    /** bounds: relative [left, top, right, bottom] (0..1) — the node centre must fall inside (±0.05). */
    val bounds: List<Double>? = null,
    /** Weight; null = default of the kind. */
    val weight: Double? = null
) {
    val effectiveWeight: Double get() = weight ?: defaultWeight(kind)

    companion object {
        const val ID = "id"
        const val DESC = "desc"
        const val TEXT = "text"
        const val CLASS_INDEX = "class_index"
        const val BOUNDS = "bounds"
        val KINDS = setOf(ID, DESC, TEXT, CLASS_INDEX, BOUNDS)
        const val DEFAULT_MIN_SCORE = 0.7

        fun defaultWeight(kind: String): Double = when (kind) {
            ID -> 1.0
            DESC -> 0.8
            TEXT -> 0.7
            CLASS_INDEX -> 0.4
            BOUNDS -> 0.3
            else -> 0.0
        }
    }
}

/** Ids (resource entry names) and/or app UI strings that must be visible. */
@Serializable
data class Expect(
    val ids: List<String> = emptyList(),
    val texts: List<String> = emptyList(),
    /** "all" (default) or "any". */
    val match: String = "all"
) {
    val isEmpty: Boolean get() = ids.isEmpty() && texts.isEmpty()
}

/** Per-playbook state on this phone. */
enum class PlaybookStatus { OK, UNVERIFIED, STALE }

/** A stored playbook + its on-device state. */
data class StoredPlaybook(
    val playbook: Playbook,
    val status: PlaybookStatus = PlaybookStatus.OK,
    /** versionCode the stale/unverified flag refers to. */
    val flaggedVersion: Long? = null,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val lastRunAt: Long = 0L,
    val importedAt: Long = 0L,
    /** "file" | "jarvis". */
    val source: String = "file"
)

/** Small record Jarvis pulls over the link to re-crawl a playbook. Ids only, no user text. */
@Serializable
data class RelearnRequest(
    @SerialName("package") val packageName: String,
    val versionCode: Long,
    val playbookId: String,
    /** Failing step index, -1 = app update outside the learned range. */
    val failedStep: Int,
    /** Short machine reason: expect_timeout | target_not_found | wrong_app | version_update | postcondition. */
    val reason: String,
    val createdAt: Long
)
