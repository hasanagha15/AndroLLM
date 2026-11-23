package io.androllm.core.appatlas.index

import io.androllm.core.accessibility.controller.SensitiveAppGuard
import io.androllm.core.appatlas.model.ActionKind
import io.androllm.core.appatlas.model.AppAtlasEntry

/**
 * Decides which apps the atlas indexes as METADATA ONLY with automation
 * disabled. Union of:
 * - [SensitiveAppGuard] (Tesla, WhatsApp, the verified banking/payment list
 *   and the owner's own additions) — the same guard that blocks ui_* taps;
 * - a conservative name heuristic for banking / payment / wallet / crypto /
 *   authenticator apps the built-in list does not know yet.
 *
 * Over-flagging is fine (the app can still be opened); under-flagging is not.
 * The heuristic never REMOVES a guard entry and never unblocks ui_* tools —
 * the guard stays the single enforcement point for taps.
 */
object SensitiveClassifier {

    private val NAME_TOKENS = listOf(
        "bank", "wallet", "crypto", "binance", "bybit", "coinbase", "paypal", "revolut",
        "authenticator", "tesla", "whatsapp", "kapital", "birbank", "leobank", "pasha", "unibank",
        "банк", "кошел", "bankı", "pul kisəsi", "phonepe", "paytm", "gpay", "simbrella", "epul",
        "kapitalbank", "rabitabank", "expressbank", "accessbank", "azericard", "ödəniş", "odenis", "оплат"
    )

    /** Short tokens matched as whole package/label segments only (substring would over-match). */
    private val SEGMENT_TOKENS = setOf("abb", "m10", "yelo", "pul", "million")

    /** Bumped whenever the rules above change — a new value forces a full re-index (gate B2). */
    const val RULES_VERSION = 2

    /** Fingerprint of every sensitivity input; a change forces a FULL re-index (gate B2). */
    fun fingerprint(extras: Set<String>): String = "$RULES_VERSION|" + extras.sorted().joinToString(",").hashCode()

    /**
     * Gate B2: sensitivity evaluated NOW (guard list, the owner's extras and the
     * heuristic may have changed since the entry was indexed). A newly
     * sensitive entry is reduced to metadata + "open" before anyone uses it.
     */
    fun effective(entry: AppAtlasEntry, extras: Set<String> = emptySet()): AppAtlasEntry {
        if (entry.sensitive) {
            return if (entry.actions.all { it.kind == ActionKind.LAUNCH } && entry.uiIds.isEmpty() && entry.uiStrings.isEmpty()) entry
            else entry.copy(actions = entry.actions.filter { it.kind == ActionKind.LAUNCH }, uiIds = emptyList(), uiStrings = emptyList())
        }
        val reason = reason(entry.packageName, entry.label, extras) ?: return entry
        return entry.copy(
            sensitive = true,
            sensitiveReason = reason,
            actions = entry.actions.filter { it.kind == ActionKind.LAUNCH },
            uiIds = emptyList(),
            uiStrings = emptyList()
        )
    }

    /** Returns null when the app may be indexed fully, otherwise the reason it is metadata-only. */
    /** [extras] = the owner's protected packages from SensitiveAppStore (K). */
    fun reason(packageName: String, label: String, extras: Set<String> = emptySet()): String? {
        if (SensitiveAppGuard.blockedReason(packageName, extras) != null) {
            return if (packageName in SensitiveAppGuard.BLOCKED_PACKAGES) {
                "protected app — use the dedicated confirmed tool, no UI automation"
            } else {
                "banking/payment app — open only, the user acts there"
            }
        }
        val segments = segments(packageName) + segments(label)
        if (segments.any { it.startsWith("pay") || it.endsWith("pay") }) return "payment app (name) — open only"
        if (segments.any { it in SEGMENT_TOKENS }) return "banking/finance app (name) — open only"
        val hay = (packageName + " " + label).lowercase()
        if (NAME_TOKENS.any { it in hay }) return "banking/finance/protected app (name) — open only"
        return null
    }

    private fun segments(s: String): List<String> =
        s.lowercase().split('.', '_', '-', ' ', ':').filter { it.isNotBlank() }
}
