package io.androllm.core.voice.stt

import io.androllm.core.common.log.ContentLog
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.voice.VoiceSettingsStore
import io.androllm.core.voice.asr.SpeechRecognitionResult
import io.androllm.core.voice.asr.SpeechRecognitionSession
import io.androllm.core.voice.asr.SpeechRecognizer
import io.androllm.core.voice.lang.VoiceLanguage
import io.androllm.core.voice.lang.VoiceLanguagePolicy
import io.androllm.core.voice.lang.WhisperLoadPlan
import io.androllm.core.voice.asr.NoWhisperModelException
import io.androllm.core.whisper.WhisperContext
import io.androllm.core.whisper.WhisperCpuConfig
import io.androllm.core.whisper.WhisperParams
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val TAG = "WhisperSTT"
private const val SAMPLE_RATE = 16000

/** Sliding window length (ms) used for live partial transcripts. */
private const val PARTIAL_WINDOW_MS = 3000L
/** Minimum samples of audio before a partial is attempted. */
private const val PARTIAL_MIN_MS = 600L

/**
 * whisper.cpp speech recognizer.
 *
 * Records the full utterance, then transcribes it offline. When streaming is
 * enabled, live partials are produced by re-transcribing a sliding window of
 * the captured audio (the same approach as the official `whisper-stream`
 * example). The loaded model context is cached and reused across turns.
 */
@Singleton
class WhisperSpeechRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: WhisperModelManager,
    private val settingsStore: VoiceSettingsStore
) : SpeechRecognizer {

    /** Context + the model it was loaded from, published atomically. */
    private class Loaded(val context: WhisperContext, val model: WhisperModel, val path: String)

    @Volatile private var loaded: Loaded? = null
    private val loadMutex = Mutex()

    override val engineLabel: String get() = "whisper.cpp"

    override val isInitialized: Boolean get() = loaded?.context?.isReady == true

    /** The whisper model file currently loaded (for debug UI). */
    val activeModelFile: File? get() = loaded?.path?.let { File(it) }

    /**
     * Picks the model for the CURRENT settings (voice language + selection)
     * and (re)loads it if it differs from the loaded one. Called at service
     * start and again at the start of every recognition session, so a
     * language/model change applies on the next utterance of a running
     * assistant. Cheap when nothing changed (settings read + size checks).
     */
    /** Loads model files; replaceable in unit tests (native lib is absent on the JVM). */
    internal var contextLoader: (String) -> WhisperContext = { WhisperContext.fromFile(it) }

    /** Whisper language actually used by the last session (debug UI + tests). */
    @Volatile var lastSessionLanguage: String? = null
        private set

    override suspend fun ensureInitialized(): Boolean = prepare() != null

    /**
     * Reads the CURRENT settings once, makes sure the matching model is loaded
     * and returns that snapshot's voice language (null = no usable model).
     * The session language comes from this same snapshot, so model and
     * language can never disagree and a change made while the assistant waited
     * for the wake word applies to the very first utterance.
     */
    private suspend fun prepare(): String? {
        return loadMutex.withLock {
            val settings = settingsStore.current()
            val language = VoiceLanguage.fromCode(settings.sttLanguage)
            val current = loaded?.takeIf { it.context.isReady }
            val plan = VoiceLanguagePolicy.planLoad(
                loadedModelId = current?.model?.id,
                selectedId = settings.whisperModel,
                language = language,
                installed = modelManager.installedModels()
            )
            when (plan) {
                is WhisperLoadPlan.NoModel -> {
                    Timber.w(
                        "$TAG: no whisper model installed that can transcribe '%s' (selected=%s) — " +
                            "download a multilingual model in Settings",
                        language.code, settings.whisperModel
                    )
                    null
                }
                is WhisperLoadPlan.Keep -> settings.sttLanguage
                is WhisperLoadPlan.Load -> {
                    val file = modelManager.modelFile(plan.model)
                    loaded?.let { old ->
                        loaded = null
                        runCatching { old.context.release() }
                        Timber.i("$TAG: unloaded ${old.model.id} (switching to ${plan.model.id})")
                    }
                    try {
                        val ctx = contextLoader(file.absolutePath)
                        loaded = Loaded(ctx, plan.model, file.absolutePath)
                        Timber.i(
                            "$TAG: loaded ${file.name} (${file.length() / (1024 * 1024)} MB) for language=%s",
                            language.code
                        )
                        settings.sttLanguage
                    } catch (t: Throwable) {
                        Timber.e(t, "$TAG: model init failed")
                        null
                    }
                }
            }
        }
    }

    override suspend fun startSession(
        language: String,
        translate: Boolean,
        numThreads: Int,
        beamSize: Int,
        temperature: Float,
        maxSeconds: Int,
        streamingEnabled: Boolean
    ): SpeechRecognitionSession {
        val current = prepare()
            ?: throw NoWhisperModelException(VoiceLanguage.fromCode(settingsStore.current().sttLanguage).code)
        val l = loaded ?: throw NoWhisperModelException(VoiceLanguage.fromCode(current).code)
        if (current != language) {
            Timber.i("$TAG: session language from current settings '%s' (caller passed '%s')", current, language)
        }
        val effective = VoiceLanguagePolicy.effectiveWhisperLanguage(current, l.model.multilingual)
        lastSessionLanguage = effective
        val threads = if (numThreads > 0) numThreads else WhisperCpuConfig.preferredThreadCount
        return WhisperSession(
            context = l.context,
            params = WhisperParams(
                language = effective,
                translate = translate,
                numThreads = threads,
                beamSize = beamSize,
                temperature = temperature
            ),
            maxSeconds = maxSeconds,
            streamingEnabled = streamingEnabled
        )
    }

    override suspend fun release() {
        loadMutex.withLock {
            val old = loaded
            loaded = null
            runCatching { old?.context?.release() }
        }
    }

    /**
     * One recording turn. Audio chunks are appended on the caller thread while
     * transcription (final or partial) runs on whisper's single dispatcher.
     */
    private class WhisperSession(
        private val context: WhisperContext,
        private val params: WhisperParams,
        private val maxSeconds: Int,
        private val streamingEnabled: Boolean
    ) : SpeechRecognitionSession {

        private val lock = Object()
        private var samples = ArrayList<Float>()
        private val startedAt = System.nanoTime()
        private var cancelled = false

        /** True while a sliding-window partial transcription is running. */
        @Volatile var partialRunning: Boolean = false
            private set

        override val elapsedMs: Long get() = (System.nanoTime() - startedAt) / 1_000_000
        override val sampleCount: Int get() = synchronized(lock) { samples.size }

        override fun append(chunk: FloatArray) {
            if (cancelled) return
            synchronized(lock) {
                samples.ensureCapacity(samples.size + chunk.size)
                for (s in chunk) samples.add(s)
            }
        }

        override fun cancel() {
            cancelled = true
            synchronized(lock) { samples = ArrayList() }
        }

        override fun release() {
            cancel()
        }

        override suspend fun partial(): SpeechRecognitionResult {
            if (!streamingEnabled) return emptyResult()
            if (partialRunning) return emptyResult()
            val snapshot = snapshotTail(PARTIAL_WINDOW_MS)
            if (snapshot.size < (SAMPLE_RATE * PARTIAL_MIN_MS / 1000).toInt()) return emptyResult()
            partialRunning = true
            try {
                val t0 = System.nanoTime()
                val transcription = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    context.transcribe(snapshot, params.copy(noContext = true))
                }
                val inferenceMs = (System.nanoTime() - t0) / 1_000_000
                return object : SpeechRecognitionResult {
                    override val text = transcription.text
                    override val confidence: Float? = null
                    override val durationMs = (snapshot.size * 1000L) / SAMPLE_RATE
                    override val inferenceMs = inferenceMs
                    override val language: String? = null
                    override val engine = "whisper.cpp"
                }
            } finally {
                partialRunning = false
            }
        }

        override suspend fun finish(): SpeechRecognitionResult {
            val full = synchronized(lock) { samples.toFloatArray() }
            val trimmed = trimSilence(full)
            if (trimmed.isEmpty()) return emptyResult()
            val t0 = System.nanoTime()
            val transcription = withContext(kotlinx.coroutines.Dispatchers.Default) {
                context.transcribe(trimmed, params)
            }
            val inferenceMs = (System.nanoTime() - t0) / 1_000_000
            val durationMs = (trimmed.size * 1000L) / SAMPLE_RATE
            Timber.i(
                "$TAG: transcribed %.1fs (%d trimmed from %.1fs) in %dms -> %s",
                durationMs / 1000f, full.size - trimmed.size, full.size / 16000f,
                inferenceMs, ContentLog.text(transcription.text)
            )
            return object : SpeechRecognitionResult {
                override val text = transcription.text
                override val confidence: Float? = null
                override val durationMs = durationMs
                override val inferenceMs = inferenceMs
                override val language: String? = null
                override val engine = "whisper.cpp"
            }
        }

        /**
         * Drops leading/trailing near-silence so whisper decodes the actual
         * utterance (the turn always starts on the wake chime + a pause, and
         * silence padding slows inference and invites hallucinations).
         */
        private fun trimSilence(data: FloatArray): FloatArray {
            val threshold = 0.008f
            var start = 0
            while (start < data.size && kotlin.math.abs(data[start]) < threshold) start++
            var end = data.size
            while (end > start && kotlin.math.abs(data[end - 1]) < threshold) end--
            if (end <= start) return FloatArray(0)
            // Keep a small speech margin at both edges (80 ms).
            start = (start - SAMPLE_RATE / 12).coerceAtLeast(0)
            end = (end + SAMPLE_RATE / 12).coerceAtMost(data.size)
            return data.copyOfRange(start, end)
        }

        private fun emptyResult(): SpeechRecognitionResult = object : SpeechRecognitionResult {
            override val text = ""
            override val confidence: Float? = null
            override val durationMs = 0L
            override val inferenceMs = 0L
            override val language: String? = null
            override val engine = "whisper.cpp"
        }

        /** Last [windowMs] of audio (for sliding-window partials). */
        private fun snapshotTail(windowMs: Long): FloatArray {
            val count = (windowMs * SAMPLE_RATE / 1000).toInt()
            synchronized(lock) {
                val size = samples.size
                val start = (size - count).coerceAtLeast(0)
                val out = FloatArray(size - start)
                for (i in start until size) out[i - start] = samples[i]
                return out
            }
        }
    }
}