package io.androllm.core.voice.stt

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.androllm.core.voice.VoiceSettingsStore
import io.androllm.core.voice.asr.NoWhisperModelException
import io.androllm.core.voice.model.VoiceSettings
import io.androllm.core.whisper.WhisperContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Gate A: a RUNNING assistant must use the current voice language and the
 * matching model on the very next utterance (no restart, no one-turn lag).
 */
class WhisperSpeechRecognizerTest {

    private val base = File("/models")
    private var cur = VoiceSettings(sttLanguage = "en", whisperModel = "base.en")
    private val installed = mutableListOf(WhisperModels.byId("base.en")!!, WhisperModels.byId("small-q8_0")!!)
    private val loadedPaths = mutableListOf<String>()

    private val store = mockk<VoiceSettingsStore> { coEvery { current() } answers { cur } }
    private val manager = mockk<WhisperModelManager> {
        every { installedModels() } answers { installed.toList() }
        every { modelFile(any()) } answers { File(base, firstArg<WhisperModel>().fileName) }
    }

    private fun recognizer() = WhisperSpeechRecognizer(mockk<Context>(relaxed = true), manager, store).apply {
        contextLoader = { path ->
            loadedPaths += File(path).name
            mockk<WhisperContext>(relaxed = true) { every { isReady } returns true }
        }
    }

    @Test fun `language switched EN to RU while running applies to the next session`() = runBlocking<Unit> {
        val r = recognizer()
        assertThat(r.ensureInitialized()).isTrue() // service start: EN, base.en
        assertThat(loadedPaths).containsExactly("ggml-base.en.bin")

        // User switches to Russian while the assistant waits for the wake word.
        cur = cur.copy(sttLanguage = "ru")
        // Stale caller snapshot still says "en" — the recognizer must not care.
        r.startSession(language = "en")
        assertThat(r.lastSessionLanguage).isEqualTo("ru")
        assertThat(loadedPaths).containsExactly("ggml-base.en.bin", "ggml-small-q8_0.bin").inOrder()
        assertThat(r.activeModelFile?.name).isEqualTo("ggml-small-q8_0.bin")
    }

    @Test fun `unchanged cur keep the loaded context`() = runBlocking<Unit> {
        cur = VoiceSettings(sttLanguage = "ru", whisperModel = "small-q8_0")
        val r = recognizer()
        r.startSession(language = "ru")
        r.startSession(language = "ru")
        assertThat(loadedPaths).containsExactly("ggml-small-q8_0.bin")
        assertThat(r.lastSessionLanguage).isEqualTo("ru")
    }

    @Test fun `AZ to AUTO language change is fresh on the first session`() = runBlocking<Unit> {
        cur = VoiceSettings(sttLanguage = "az", whisperModel = "small-q8_0")
        val r = recognizer()
        r.startSession(language = "az")
        cur = cur.copy(sttLanguage = "auto")
        r.startSession(language = "az")
        assertThat(r.lastSessionLanguage).isEqualTo("auto")
    }

    @Test fun `russian with only english models throws NoWhisperModelException`() {
        installed.removeAll { it.multilingual }
        cur = VoiceSettings(sttLanguage = "ru", whisperModel = "base.en")
        val r = recognizer()
        val e = assertThrows(NoWhisperModelException::class.java) { runBlocking { r.startSession(language = "ru") } }
        assertThat(e.language).isEqualTo("ru")
    }

    @Test fun `english-only model forces en for auto`() = runBlocking<Unit> {
        installed.removeAll { it.multilingual }
        cur = VoiceSettings(sttLanguage = "auto", whisperModel = "base.en")
        val r = recognizer()
        r.startSession(language = "auto")
        assertThat(r.lastSessionLanguage).isEqualTo("en")
    }
}
