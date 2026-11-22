package io.androllm.core.voice.lang

import io.androllm.core.voice.stt.WhisperModel
import io.androllm.core.voice.stt.WhisperModels

/**
 * Voice (speech recognition + speech synthesis) language of the assistant.
 *
 * Persisted as [io.androllm.core.voice.model.VoiceSettings.sttLanguage]
 * ([code]). Russian is the default for this fork. The wake word (sherpa-onnx
 * KWS, "hey andro") is language independent and not affected.
 */
enum class VoiceLanguage(val code: String, val label: String) {
    RU("ru", "Русский"),
    EN("en", "English"),
    AZ("az", "Azərbaycanca"),
    AUTO("auto", "Авто (определять)");

    /** English-only (`*.en`) whisper models can't transcribe this language. */
    val needsMultilingualModel: Boolean get() = this != EN

    companion object {
        val DEFAULT = RU

        /** Parses a persisted code; blank -> [DEFAULT], unknown ISO code -> [AUTO]-like handling by the caller. */
        fun fromCode(code: String?): VoiceLanguage {
            val c = code?.trim()?.lowercase().orEmpty()
            if (c.isEmpty()) return DEFAULT
            return entries.firstOrNull { it.code == c } ?: AUTO
        }
    }
}

/** What the recognizer must do before a session (see [VoiceLanguagePolicy.planLoad]). */
sealed interface WhisperLoadPlan {
    /** Nothing installed can transcribe the language. */
    data object NoModel : WhisperLoadPlan
    /** The loaded model is still the right one. */
    data class Keep(val model: WhisperModel) : WhisperLoadPlan
    /** Load [model] (unloading whatever is loaded now). */
    data class Load(val model: WhisperModel) : WhisperLoadPlan
}

/** Which offline TTS voice speaks a sentence. */
enum class TtsVoiceKind { BUNDLED_ENGLISH, RUSSIAN }

/**
 * Pure selection logic (unit-tested): whisper language code, model choice,
 * TTS voice routing.
 */
object VoiceLanguagePolicy {

    /**
     * Multilingual models in preference order for non-English speech on a
     * Snapdragon 8 Gen 3 class phone: small Q8 is the quality/latency sweet
     * spot for Russian; large-v3-turbo Q5 is better but ~2-3x slower.
     */
    val MULTILINGUAL_PREFERENCE: List<String> = listOf(
        "small-q8_0", "small-q5_1", "small", "large-v3-turbo-q5_0", "large-v3-turbo",
        "base-q8_0", "base", "tiny"
    )

    private val ENGLISH_PREFERENCE: List<String> = listOf(
        "base.en", "small.en", "small-q8_0", "small-q5_1", "small", "tiny.en", "medium.en",
        "base-q8_0", "base", "large-v3-turbo-q5_0", "large-v3-turbo", "tiny"
    )

    /**
     * Language code passed to whisper.cpp. Known languages map to their ISO
     * code ("auto" = auto-detect); an unknown persisted ISO code is passed
     * through unchanged (whisper knows ~100 languages).
     */
    fun whisperLanguage(rawCode: String?): String {
        val c = rawCode?.trim()?.lowercase().orEmpty()
        if (c.isEmpty()) return VoiceLanguage.DEFAULT.code
        return c
    }

    /**
     * Language actually sent to whisper for the loaded model: an English-only
     * model can only decode English, so anything else is forced to "en"
     * (passing "ru" to a *.en model yields garbage).
     */
    fun effectiveWhisperLanguage(rawCode: String?, modelMultilingual: Boolean): String =
        if (modelMultilingual) whisperLanguage(rawCode) else "en"

    fun isCompatible(model: WhisperModel, language: VoiceLanguage): Boolean =
        !language.needsMultilingualModel || model.multilingual

    /** Model to download for [language] on a device with [totalRamGb]. */
    fun recommendedModel(language: VoiceLanguage, totalRamGb: Long): WhisperModel {
        val id = when {
            language == VoiceLanguage.EN -> "base.en"
            totalRamGb >= 6 -> "small-q8_0"
            else -> "base-q8_0"
        }
        return WhisperModels.byId(id)!!
    }

    /**
     * Picks the model to load: the selected one when installed and able to
     * transcribe [language]; else the best installed compatible model; for
     * [VoiceLanguage.AUTO] (only) falls back to an English-only model (it
     * then runs with language "en"). Returns null when nothing usable is
     * installed — the caller reports "download a model".
     */
    fun resolveModel(
        selectedId: String,
        language: VoiceLanguage,
        installed: List<WhisperModel>
    ): WhisperModel? {
        if (installed.isEmpty()) return null
        val selected = installed.firstOrNull { it.id == selectedId }
        if (selected != null && isCompatible(selected, language)) return selected
        val order = if (language == VoiceLanguage.EN) ENGLISH_PREFERENCE else MULTILINGUAL_PREFERENCE
        order.forEach { id ->
            installed.firstOrNull { it.id == id }?.let { if (isCompatible(it, language)) return it }
        }
        installed.firstOrNull { isCompatible(it, language) }?.let { return it }
        return if (language == VoiceLanguage.AUTO) selected ?: installed.first() else null
    }

    /**
     * Decides, before every recognition session, whether the loaded model
     * ([loadedModelId], null = none) can stay or another one must be loaded
     * for the current language/selection.
     */
    fun planLoad(
        loadedModelId: String?,
        selectedId: String,
        language: VoiceLanguage,
        installed: List<WhisperModel>
    ): WhisperLoadPlan {
        val target = resolveModel(selectedId, language, installed) ?: return WhisperLoadPlan.NoModel
        return if (target.id == loadedModelId) WhisperLoadPlan.Keep(target) else WhisperLoadPlan.Load(target)
    }

    private val CYRILLIC = Regex("\\p{IsCyrillic}")

    fun containsCyrillic(text: String): Boolean = CYRILLIC.containsMatchIn(text)

    /**
     * TTS voice for one sentence. RU -> Russian voice; EN -> bundled English;
     * AUTO -> Russian when the sentence has Cyrillic letters, else English;
     * AZ -> no Azerbaijani Piper voice exists in sherpa-onnx, Latin text goes
     * to English, Cyrillic to Russian. Russian falls back to English when the
     * Russian voice is not downloaded.
     */
    fun ttsVoiceFor(language: VoiceLanguage, text: String, russianInstalled: Boolean): TtsVoiceKind {
        val wantRussian = when (language) {
            VoiceLanguage.RU -> true
            VoiceLanguage.EN -> false
            VoiceLanguage.AZ, VoiceLanguage.AUTO -> containsCyrillic(text)
        }
        return if (wantRussian && russianInstalled) TtsVoiceKind.RUSSIAN else TtsVoiceKind.BUNDLED_ENGLISH
    }
}
