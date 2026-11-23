package io.androllm.core.appatlas.index

import io.androllm.core.accessibility.controller.SensitiveAppGuard

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
        "банк", "кошел", "bankı", "pul kisəsi"
    )

    /** Returns null when the app may be indexed fully, otherwise the reason it is metadata-only. */
    fun reason(packageName: String, label: String): String? {
        if (SensitiveAppGuard.blockedReason(packageName) != null) {
            return if (packageName in SensitiveAppGuard.BLOCKED_PACKAGES) {
                "protected app — use the dedicated confirmed tool, no UI automation"
            } else {
                "banking/payment app — open only, the user acts there"
            }
        }
        val segments = segments(packageName) + segments(label)
        if (segments.any { it.startsWith("pay") || it.endsWith("pay") }) return "payment app (name) — open only"
        val hay = (packageName + " " + label).lowercase()
        if (NAME_TOKENS.any { it in hay }) return "banking/finance/protected app (name) — open only"
        return null
    }

    private fun segments(s: String): List<String> =
        s.lowercase().split('.', '_', '-', ' ', ':').filter { it.isNotBlank() }
}
