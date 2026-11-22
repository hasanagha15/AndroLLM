package io.androllm.core.voice.lang

import com.google.common.truth.Truth.assertThat
import io.androllm.core.voice.model.VoiceSettings
import io.androllm.core.voice.stt.WhisperModels
import io.androllm.core.voice.tts.RussianTtsText
import io.androllm.core.voice.download.TarPaths
import io.androllm.core.voice.tts.TtsVoices
import org.junit.Test

class VoiceLanguagePolicyTest {

    private fun m(id: String) = WhisperModels.byId(id)!!

    // ── language parsing / whisper code ──────────────────────────────────────

    @Test fun `fork defaults are russian and a multilingual model`() {
        val s = VoiceSettings()
        assertThat(VoiceLanguage.fromCode(s.sttLanguage)).isEqualTo(VoiceLanguage.RU)
        assertThat(m(s.whisperModel).multilingual).isTrue()
        assertThat(VoiceLanguage.DEFAULT).isEqualTo(VoiceLanguage.RU)
    }

    @Test fun `fromCode parses known codes case-insensitively`() {
        assertThat(VoiceLanguage.fromCode("RU")).isEqualTo(VoiceLanguage.RU)
        assertThat(VoiceLanguage.fromCode(" en ")).isEqualTo(VoiceLanguage.EN)
        assertThat(VoiceLanguage.fromCode("az")).isEqualTo(VoiceLanguage.AZ)
        assertThat(VoiceLanguage.fromCode("auto")).isEqualTo(VoiceLanguage.AUTO)
    }

    @Test fun `fromCode blank is default and unknown is auto`() {
        assertThat(VoiceLanguage.fromCode(null)).isEqualTo(VoiceLanguage.RU)
        assertThat(VoiceLanguage.fromCode("")).isEqualTo(VoiceLanguage.RU)
        assertThat(VoiceLanguage.fromCode("de")).isEqualTo(VoiceLanguage.AUTO)
    }

    @Test fun `whisper language passes codes through`() {
        assertThat(VoiceLanguagePolicy.whisperLanguage("ru")).isEqualTo("ru")
        assertThat(VoiceLanguagePolicy.whisperLanguage("AZ")).isEqualTo("az")
        assertThat(VoiceLanguagePolicy.whisperLanguage("auto")).isEqualTo("auto")
        assertThat(VoiceLanguagePolicy.whisperLanguage("de")).isEqualTo("de")
        assertThat(VoiceLanguagePolicy.whisperLanguage(null)).isEqualTo("ru")
    }

    @Test fun `english-only model forces en`() {
        assertThat(VoiceLanguagePolicy.effectiveWhisperLanguage("ru", modelMultilingual = false)).isEqualTo("en")
        assertThat(VoiceLanguagePolicy.effectiveWhisperLanguage("auto", modelMultilingual = false)).isEqualTo("en")
        assertThat(VoiceLanguagePolicy.effectiveWhisperLanguage("ru", modelMultilingual = true)).isEqualTo("ru")
        assertThat(VoiceLanguagePolicy.effectiveWhisperLanguage("auto", modelMultilingual = true)).isEqualTo("auto")
    }

    // ── model selection ──────────────────────────────────────────────────────

    @Test fun `recommended model per language and ram`() {
        assertThat(VoiceLanguagePolicy.recommendedModel(VoiceLanguage.RU, 12).id).isEqualTo("small-q8_0")
        assertThat(VoiceLanguagePolicy.recommendedModel(VoiceLanguage.AZ, 12).id).isEqualTo("small-q8_0")
        assertThat(VoiceLanguagePolicy.recommendedModel(VoiceLanguage.AUTO, 4).id).isEqualTo("base-q8_0")
        assertThat(VoiceLanguagePolicy.recommendedModel(VoiceLanguage.EN, 12).id).isEqualTo("base.en")
        assertThat(WhisperModels.recommendedForDevice(12).multilingual).isTrue()
    }

    @Test fun `resolve keeps selected compatible model`() {
        val installed = listOf(m("base.en"), m("small-q5_1"), m("small-q8_0"))
        assertThat(VoiceLanguagePolicy.resolveModel("small-q5_1", VoiceLanguage.RU, installed)?.id)
            .isEqualTo("small-q5_1")
    }

    @Test fun `resolve skips english-only selection for russian`() {
        val installed = listOf(m("base.en"), m("base"), m("small-q8_0"))
        assertThat(VoiceLanguagePolicy.resolveModel("base.en", VoiceLanguage.RU, installed)?.id)
            .isEqualTo("small-q8_0")
    }

    @Test fun `resolve returns null for russian with only english models`() {
        val installed = listOf(m("base.en"), m("tiny.en"))
        assertThat(VoiceLanguagePolicy.resolveModel("base.en", VoiceLanguage.RU, installed)).isNull()
        assertThat(VoiceLanguagePolicy.resolveModel("base.en", VoiceLanguage.AZ, installed)).isNull()
    }

    @Test fun `auto falls back to english-only model`() {
        val installed = listOf(m("base.en"))
        assertThat(VoiceLanguagePolicy.resolveModel("small-q8_0", VoiceLanguage.AUTO, installed)?.id)
            .isEqualTo("base.en")
    }

    @Test fun `english accepts any model and prefers base en`() {
        val installed = listOf(m("small-q8_0"), m("base.en"))
        assertThat(VoiceLanguagePolicy.resolveModel("missing", VoiceLanguage.EN, installed)?.id).isEqualTo("base.en")
        assertThat(VoiceLanguagePolicy.resolveModel("small-q8_0", VoiceLanguage.EN, installed)?.id).isEqualTo("small-q8_0")
    }

    @Test fun `resolve with nothing installed is null`() {
        assertThat(VoiceLanguagePolicy.resolveModel("small-q8_0", VoiceLanguage.RU, emptyList())).isNull()
    }

    // ── reload planning (running assistant, gate Б1) ─────────────────────────

    @Test fun `english model loaded and language switched to russian forces a reload`() {
        val installed = listOf(m("base.en"), m("small-q8_0"))
        val plan = VoiceLanguagePolicy.planLoad("base.en", "base.en", VoiceLanguage.RU, installed)
        assertThat(plan).isEqualTo(WhisperLoadPlan.Load(m("small-q8_0")))
    }

    @Test fun `same model and compatible language keeps the loaded context`() {
        val installed = listOf(m("base.en"), m("small-q8_0"))
        assertThat(VoiceLanguagePolicy.planLoad("small-q8_0", "small-q8_0", VoiceLanguage.AZ, installed))
            .isEqualTo(WhisperLoadPlan.Keep(m("small-q8_0")))
    }

    @Test fun `selecting another installed model reloads`() {
        val installed = listOf(m("small-q5_1"), m("small-q8_0"))
        assertThat(VoiceLanguagePolicy.planLoad("small-q8_0", "small-q5_1", VoiceLanguage.RU, installed))
            .isEqualTo(WhisperLoadPlan.Load(m("small-q5_1")))
    }

    @Test fun `english model loaded and russian requested without multilingual model is NoModel`() {
        val installed = listOf(m("base.en"))
        assertThat(VoiceLanguagePolicy.planLoad("base.en", "base.en", VoiceLanguage.RU, installed))
            .isEqualTo(WhisperLoadPlan.NoModel)
    }

    @Test fun `nothing loaded yet loads the resolved model`() {
        assertThat(VoiceLanguagePolicy.planLoad(null, "small-q8_0", VoiceLanguage.RU, listOf(m("small-q8_0"))))
            .isEqualTo(WhisperLoadPlan.Load(m("small-q8_0")))
    }

    // ── catalog integrity ────────────────────────────────────────────────────

    @Test fun `catalog has real sha256 and pinned urls`() {
        val hex64 = "[0-9a-f]{64}"
        WhisperModels.ALL.forEach {
            assertThat(it.sha256).matches(hex64)
            assertThat(it.downloadUrl).contains("/resolve/${WhisperModels.HF_REVISION}/")
            assertThat(it.multilingual).isEqualTo(!it.id.contains(".en"))
            assertThat(it.sizeBytes).isGreaterThan(10_000_000L)
        }
        assertThat(WhisperModels.ALL.map { it.id }.toSet().size).isEqualTo(WhisperModels.ALL.size)
        VoiceLanguagePolicy.MULTILINGUAL_PREFERENCE.forEach { assertThat(WhisperModels.byId(it)?.multilingual).isTrue() }
    }

    @Test fun `small q8 catalog entry matches huggingface`() {
        val s = m("small-q8_0")
        assertThat(s.sizeBytes).isEqualTo(264_464_607L)
        assertThat(s.sha256).isEqualTo("49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f")
    }

    @Test fun `tts voices have sha256 and release urls`() {
        TtsVoices.ALL.forEach {
            assertThat(it.sha256).matches("[0-9a-f]{64}")
            assertThat(it.url).startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/")
            assertThat(it.url).endsWith(it.rootDir + ".tar.bz2")
            assertThat(it.languageCode).isEqualTo("ru")
        }
    }

    // ── TTS routing ──────────────────────────────────────────────────────────

    @Test fun `tts routing by language and script`() {
        val ru = "Привет, как дела?"
        val en = "Hello there."
        assertThat(VoiceLanguagePolicy.ttsVoiceFor(VoiceLanguage.RU, en, true)).isEqualTo(TtsVoiceKind.RUSSIAN)
        assertThat(VoiceLanguagePolicy.ttsVoiceFor(VoiceLanguage.EN, ru, true)).isEqualTo(TtsVoiceKind.BUNDLED_ENGLISH)
        assertThat(VoiceLanguagePolicy.ttsVoiceFor(VoiceLanguage.AUTO, ru, true)).isEqualTo(TtsVoiceKind.RUSSIAN)
        assertThat(VoiceLanguagePolicy.ttsVoiceFor(VoiceLanguage.AUTO, en, true)).isEqualTo(TtsVoiceKind.BUNDLED_ENGLISH)
        assertThat(VoiceLanguagePolicy.ttsVoiceFor(VoiceLanguage.AZ, "Salam, necəsən?", true)).isEqualTo(TtsVoiceKind.BUNDLED_ENGLISH)
    }

    @Test fun `tts falls back to english when russian voice missing`() {
        assertThat(VoiceLanguagePolicy.ttsVoiceFor(VoiceLanguage.RU, "Привет", false)).isEqualTo(TtsVoiceKind.BUNDLED_ENGLISH)
    }

    @Test fun `russian text cleanup strips markdown and emoji`() {
        assertThat(RussianTtsText.clean("**Привет** 👋  мир #1")).isEqualTo("Привет мир 1")
    }

    // ── tar safety ───────────────────────────────────────────────────────────

    @Test fun `tar paths stay inside the voice dir`() {
        val p = "vits-piper-ru_RU-irina-medium-int8/"
        assertThat(TarPaths.relativeInside("vits-piper-ru_RU-irina-medium-int8/tokens.txt", p)).isEqualTo("tokens.txt")
        assertThat(TarPaths.relativeInside("./vits-piper-ru_RU-irina-medium-int8/espeak-ng-data/ru_dict", p))
            .isEqualTo("espeak-ng-data/ru_dict")
        assertThat(TarPaths.relativeInside("vits-piper-ru_RU-irina-medium-int8/", p)).isNull()
        assertThat(TarPaths.relativeInside("vits-piper-ru_RU-irina-medium-int8/../evil", p)).isNull()
        assertThat(TarPaths.relativeInside("other/tokens.txt", p)).isNull()
    }
}
