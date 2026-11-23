package io.androllm.core.accessibility.controller

import android.content.Context

/**
 * Apps the assistant must never drive through the accessibility service.
 * Taps and typing there bypass the dedicated, confirmed tools (tesla_command,
 * whatsapp_reply): in the Tesla app a tap can unlock the car, open the trunk
 * or start driving; in WhatsApp a tap sends a message to anyone. The model
 * has to use the confirmed Jarvis tools instead.
 *
 * Banking / payment / wallet apps (F1): the assistant may OPEN them
 * (launch_app) so the user can pay, but must never tap or type inside — a
 * payment is only ever made by the user. Built-in list = package names
 * verified on Google Play; the user can add more in Settings → Memory →
 * Personal context ([extraPackages], stored on device).
 */
object SensitiveAppGuard {

    val BLOCKED_PACKAGES: Set<String> = setOf(
        "com.teslamotors.tesla",
        "com.whatsapp",
        "com.whatsapp.w4b"
    )

    /** Banking / payment / wallet apps (package names verified on Google Play). */
    val PAYMENT_PACKAGES: Set<String> = setOf(
        "az.kapitalbank.mbanking", // Birbank
        "az.kapitalbank.mushteribank", // Birbank Biznes
        "com.google.android.apps.walletnfcrel", // Google Wallet
        "com.samsung.android.spay", // Samsung Wallet
        "com.paypal.android.p2pmobile" // PayPal
    )

    /** User-added payment/banking packages (on-device setting). */
    @Volatile
    var extraPackages: Set<String> = emptySet()
        private set

    const val PREFS_NAME = "sensitive_app_guard"
    const val KEY_EXTRA_PACKAGES = "extra_payment_packages"

    /** Parses a comma / whitespace separated package list; invalid entries are dropped. */
    fun parsePackages(raw: String?): Set<String> =
        raw.orEmpty()
            .split(',', ';', '\n', ' ', '\t')
            .map { it.trim() }
            .filter { isPackageName(it) }
            .toSet()

    fun setExtraPackages(packages: Set<String>) {
        extraPackages = packages
    }

    /** Loads the user list from on-device prefs. Safe to call repeatedly. */
    fun loadExtras(context: Context) {
        runCatching {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_EXTRA_PACKAGES, "")
            extraPackages = parsePackages(raw)
        }
    }

    /** Saves the user list to on-device prefs and applies it. */
    fun saveExtras(context: Context, raw: String) {
        val parsed = parsePackages(raw)
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_EXTRA_PACKAGES, parsed.joinToString(", ")).apply()
        }
        extraPackages = parsed
    }

    /** Null when [packageName] may be automated, otherwise the refusal message. */
    fun blockedReason(packageName: CharSequence?): String? {
        val pkg = packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty()) return null
        if (pkg in PAYMENT_PACKAGES || pkg in extraPackages) {
            return "UI automation is blocked in $pkg: it is a banking/payment app. The assistant may only " +
                "open it; the user taps, types and pays there themselves."
        }
        if (pkg !in BLOCKED_PACKAGES) return null
        val hint = if (pkg.startsWith("com.whatsapp")) "whatsapp_reply" else "tesla_status / tesla_command"
        return "UI automation is blocked in $pkg for safety. Use the dedicated tool ($hint), which asks the user first."
    }

    /** "a.b" style: segments of letters/digits/underscore, first starts with a letter, at least one dot. */
    private fun isPackageName(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size < 2) return false
        if (!parts[0].firstOrNull().let { it != null && it.isLetter() }) return false
        return parts.all { p -> p.isNotEmpty() && p.all { it.isLetterOrDigit() || it == '_' } }
    }
}
