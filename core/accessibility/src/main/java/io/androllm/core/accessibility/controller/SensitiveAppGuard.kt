package io.androllm.core.accessibility.controller

/**
 * Apps the assistant must never drive through the accessibility service.
 * Taps and typing there bypass the dedicated, confirmed tools (tesla_command,
 * whatsapp_reply): in the Tesla app a tap can unlock the car, open the trunk
 * or start driving; in WhatsApp a tap sends a message to anyone. The model
 * has to use the confirmed Jarvis tools instead.
 */
object SensitiveAppGuard {

    val BLOCKED_PACKAGES: Set<String> = setOf(
        "com.teslamotors.tesla",
        "com.whatsapp",
        "com.whatsapp.w4b"
    )

    /** Null when [packageName] may be automated, otherwise the refusal message. */
    fun blockedReason(packageName: CharSequence?): String? {
        val pkg = packageName?.toString()?.trim().orEmpty()
        if (pkg !in BLOCKED_PACKAGES) return null
        val hint = if (pkg.startsWith("com.whatsapp")) "whatsapp_reply" else "tesla_status / tesla_command"
        return "UI automation is blocked in $pkg for safety. Use the dedicated tool ($hint), which asks the user first."
    }
}
