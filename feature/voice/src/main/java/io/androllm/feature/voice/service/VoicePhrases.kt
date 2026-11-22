package io.androllm.feature.voice.service

import io.androllm.core.voice.lang.VoiceLanguage

/**
 * Short service phrases the voice loop speaks itself (not model output), in
 * the current voice language. AUTO speaks Russian (the fork default).
 * Azerbaijani has no Piper voice, so TTS reads it with the English voice —
 * still better than a Russian phrase for an az-speaking owner.
 */
object VoicePhrases {

    private enum class L { RU, EN, AZ }

    private fun of(language: VoiceLanguage): L = when (language) {
        VoiceLanguage.EN -> L.EN
        VoiceLanguage.AZ -> L.AZ
        VoiceLanguage.RU, VoiceLanguage.AUTO -> L.RU
    }

    /** Tap-only card raised in a voice turn: it cannot be approved by voice. */
    fun confirmInApp(language: VoiceLanguage): String = when (of(language)) {
        L.RU -> "Подтвердите это действие в приложении."
        L.EN -> "Please confirm this action in the app."
        L.AZ -> "Bu əməliyyatı tətbiqdə təsdiqləyin."
    }

    /** The card was denied, timed out or was cancelled. */
    fun cancelled(language: VoiceLanguage): String = when (of(language)) {
        L.RU -> "Отменено."
        L.EN -> "Cancelled."
        L.AZ -> "Ləğv edildi."
    }

    /** A remote Jarvis task holds the turn pipeline. */
    fun busyRemote(language: VoiceLanguage): String = when (of(language)) {
        L.RU -> "Занято: выполняется удалённая задача Jarvis. Подождите и повторите позже."
        L.EN -> "Busy: a remote Jarvis task is running. Please wait and try again shortly."
        L.AZ -> "Məşğuldur: uzaqdan Jarvis tapşırığı icra olunur. Gözləyin və bir az sonra təkrar edin."
    }

    /** Notification title for a tap-only card raised in a voice turn. */
    fun notificationTitle(language: VoiceLanguage, toolDisplayName: String): String = when (of(language)) {
        L.RU -> "Подтвердите: $toolDisplayName"
        L.EN -> "Confirm: $toolDisplayName"
        L.AZ -> "Təsdiqləyin: $toolDisplayName"
    }

    /** Notification hint line under the action summary. */
    fun notificationHint(language: VoiceLanguage): String = when (of(language)) {
        L.RU -> "Голосом это действие не подтверждается. Нажмите, чтобы открыть карточку в приложении."
        L.EN -> "This action can't be approved by voice. Tap to open the card in the app."
        L.AZ -> "Bu əməliyyat səslə təsdiqlənmir. Tətbiqdə kartı açmaq üçün toxunun."
    }
}
