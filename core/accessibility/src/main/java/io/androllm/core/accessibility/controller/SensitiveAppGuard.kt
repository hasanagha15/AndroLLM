package io.androllm.core.accessibility.controller

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
 * Personal context (stored on device by [SensitiveAppStore] and passed in
 * as `extras`; no global mutable state here).
 */
object SensitiveAppGuard {

    val BLOCKED_PACKAGES: Set<String> = setOf(
        "com.teslamotors.tesla",
        "com.whatsapp",
        "com.whatsapp.w4b"
    )

    /**
     * Banking / payment / wallet apps. Every ID was checked on its Google Play
     * details page (title + developer) on 2026-11-23; unverified ones are not
     * listed (users add those in Settings).
     */
    val PAYMENT_PACKAGES: Set<String> = setOf(
        // Azerbaijan: banks
        "az.kapitalbank.mbanking", // Birbank (Kapital Bank)
        "az.kapitalbank.mushteribank", // Birbank Biznes
        "az.kapitalbank.birbankinvest", // Birbank Invest
        "iba.mobilbank", // ABB - Mobile
        "abb.corporate.mobile", // ABB Biz
        "com.ftfarm.leo", // Leobank (Unibank)
        "az.pashabank.mobilebank", // PASHA Bank
        "az.pashabank.sme", // PASHA Bank Business
        "az.unibank.mbanking", // UBank by Unibank
        "az.unibank.mbanking.corporate", // UBank Biznes
        "com.brmobilshobe", // Bank Respublika: Mobil Şöbə
        "az.bankrespublika.moffice", // Bank Respublika: Mobil Ofis Biznes
        "az.yelobank.app", // Yelo
        "az.yelo.business.app", // Yelo Business
        "az.yelo.business", // Yelo Business (second listing)
        "az.myaccess.mobile", // AccessBank: myAccess mobile
        "az.xalqbank.mobile", // Xalq Bank: XalqOnline
        "com.rabitabank", // Rabita Mobile
        "com.rabitabank.corporate", // Rabita Business
        "com.rabitabank.junior", // Rabita Junior
        "com.bankofbaku.v2.dostbank", // Bank of Baku: Dostbank
        "az.expressbank.e24", // Expressbank: Express24
        "az.expressbank.businessapp", // Express Business
        "az.ziraat.mobile", // Ziraat Bank Azerbaijan
        "az.afbbanknewdesign", // AFB Mobile
        "az.turanbank.mlb", // TuranBank Mobilbank
        "az.premiumbank.app", // Premium Mobile Bank
        "az.azerturkbank.atb360", // Azər Türk Bank: atb360
        "az.btb.mobilebanking", // Bank BTB Mobile
        // Azerbaijan: wallets
        "com.m10", // m10 (PashaPay)
        "expressbank.wallet.expresspay", // ExpressPay Wallet
        "hesab.az", // Hesab.az (Goldenpay)
        "com.mpayapp", // MPAY
        // International wallets / payments
        "com.google.android.apps.walletnfcrel", // Google Wallet
        "com.samsung.android.spay", // Samsung Wallet
        "com.paypal.android.p2pmobile", // PayPal
        "com.venmo", // Venmo
        "com.squareup.cash", // Cash App
        "com.revolut.revolut", // Revolut
        "com.transferwise.android", // Wise
        "com.payoneer.android", // Payoneer
        "com.moneybookers.skrillpayments", // Skrill
        "com.moneybookers.skrillpayments.neteller", // Neteller
        "com.myklarnamobile", // Klarna
        "lt.lemonlabs.android.paysera", // Paysera
        "com.westernunion.moneytransferr3app.eu", // Western Union
        "com.westernunion.moneytransferr3app.eu2", // Western Union (EU 2)
        "com.westernunion.android.mtapp" // Western Union (US)
    )

    /** Parses a comma / whitespace separated package list; invalid entries are dropped. */
    fun parsePackages(raw: String?): Set<String> =
        raw.orEmpty()
            .split(',', ';', '\n', ' ', '\t')
            .map { it.trim() }
            .filter { isPackageName(it) }
            .toSet()

    /**
     * Null when [packageName] may be automated, otherwise the refusal message.
     * [extras] = the user's own banking/payment packages ([SensitiveAppStore]).
     */
    fun blockedReason(packageName: CharSequence?, extras: Set<String> = emptySet()): String? {
        val pkg = packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty()) return null
        if (pkg in PAYMENT_PACKAGES || pkg in extras) {
            return "UI automation is blocked in $pkg: it is a banking/payment app. The assistant may only " +
                "open it; the user taps, types and pays there themselves."
        }
        if (pkg !in BLOCKED_PACKAGES) return null
        val hint = if (pkg.startsWith("com.whatsapp")) "whatsapp_reply" else "tesla_status / tesla_command"
        return "UI automation is blocked in $pkg for safety. Use the dedicated tool ($hint), which asks the user first."
    }

    /**
     * Null when the screen of [packageName] may be read (accessibility tree,
     * screenshot, OCR), otherwise the refusal message (K).
     *
     * Same set as [blockedReason]: banking/payment apps (balances, card
     * numbers, IBANs would land in the prompt and, in cloud mode, at the
     * provider), and Tesla/WhatsApp. Reading those two is NOT a deliberate
     * feature: the agent reads WhatsApp through the bot API (whatsapp_inbox /
     * whatsapp_pending, framed as untrusted data) and the car through
     * tesla_status. A raw screen dump would leak other people's chats or the
     * car's location unframed, and is a prompt-injection channel.
     */
    fun screenReadBlockedReason(packageName: CharSequence?, extras: Set<String> = emptySet()): String? {
        val pkg = packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty()) return null
        if (pkg in PAYMENT_PACKAGES || pkg in extras) {
            return "Screen reading is blocked in $pkg: it is a banking/payment app, its screen is hidden " +
                "from the assistant. Ask the user to read the needed detail themselves."
        }
        if (pkg !in BLOCKED_PACKAGES) return null
        val hint = if (pkg.startsWith("com.whatsapp")) "whatsapp_inbox / whatsapp_pending" else "tesla_status"
        return "Screen reading is blocked in $pkg for privacy. Use the dedicated tool ($hint) instead."
    }

    /** Refusal when the active window cannot be identified (fail-closed, K). */
    const val UNKNOWN_WINDOW_REASON =
        "Screen reading refused: the active window could not be identified (the screen may be switching apps). Try again in a moment."

    /**
     * Read gate for the accessibility tree (K, fail-closed): refuses when the
     * active window's package OR the last package seen in accessibility events
     * ([trackedPackage]) is protected. Null [activePackage] alone is not a
     * refusal here; the caller treats an unknown window as "nothing to read".
     */
    fun screenReadGate(
        activePackage: CharSequence?,
        trackedPackage: CharSequence?,
        extras: Set<String> = emptySet()
    ): String? {
        val active = activePackage?.toString()?.trim().orEmpty()
        val tracked = trackedPackage?.toString()?.trim().orEmpty()
        screenReadBlockedReason(active, extras)?.let { return it }
        if (tracked != active) screenReadBlockedReason(tracked, extras)?.let { return it }
        return null
    }

    /**
     * Gate for a full-display screenshot (OCR, scan_qr). Fail-closed: refuses
     * when the active window is unknown, when the active or tracked app is
     * protected, and when ANY visible application window (split screen,
     * picture-in-picture, floating window; [visibleAppPackages], null = window
     * whose app could not be identified) belongs to a protected app.
     */
    fun screenCaptureBlockedReason(
        activePackage: CharSequence?,
        trackedPackage: CharSequence?,
        visibleAppPackages: List<CharSequence?>,
        extras: Set<String> = emptySet()
    ): String? {
        if (activePackage.isNullOrBlank()) return UNKNOWN_WINDOW_REASON
        screenReadGate(activePackage, trackedPackage, extras)?.let { return it }
        for (p in visibleAppPackages) {
            if (p.isNullOrBlank()) {
                return "Screen capture refused: an app window on screen could not be identified."
            }
            screenReadBlockedReason(p, extras)?.let {
                return "Screen capture refused: a protected app is visible on screen (split screen / " +
                    "picture-in-picture). $it"
            }
        }
        return null
    }

    /** "a.b" style: segments of letters/digits/underscore, first starts with a letter, at least one dot. */
    private fun isPackageName(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size < 2) return false
        if (!parts[0].firstOrNull().let { it != null && it.isLetter() }) return false
        return parts.all { p -> p.isNotEmpty() && p.all { it.isLetterOrDigit() || it == '_' } }
    }
}
