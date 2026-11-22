package io.androllm.core.voice.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.voice.download.SafeTarBz2
import io.androllm.core.voice.download.VerifiedDownload
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * A downloadable sherpa-onnx Piper (VITS) voice. Tarballs come from the
 * k2-fsa/sherpa-onnx `tts-models` GitHub release; [sha256] is the digest
 * GitHub publishes for the asset (verified 2026-11-22).
 */
data class TtsVoice(
    val id: String,
    val displayName: String,
    val languageCode: String,
    val url: String,
    val sha256: String,
    /** Download size (tar.bz2). */
    val archiveBytes: Long,
    /** Top-level directory inside the tarball. */
    val rootDir: String,
    /** Model file name inside [rootDir]. */
    val modelFile: String
)

object TtsVoices {
    private const val RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    /** Female, int8: 21 MB download, ~36 MB on disk (18.6 MB model + espeak-ng-data). */
    val RU_IRINA_INT8 = TtsVoice(
        id = "ru_RU-irina-medium-int8",
        displayName = "Ирина (ru_RU, int8)",
        languageCode = "ru",
        url = RELEASE + "vits-piper-ru_RU-irina-medium-int8.tar.bz2",
        sha256 = "b0000a509f7551a80742eed5c43b8eb03f469cb9f0a42f6feee96ce0da0ebab8",
        archiveBytes = 21_149_417,
        rootDir = "vits-piper-ru_RU-irina-medium-int8",
        modelFile = "ru_RU-irina-medium.onnx"
    )

    /** Male, int8. */
    val RU_DMITRI_INT8 = TtsVoice(
        id = "ru_RU-dmitri-medium-int8",
        displayName = "Дмитрий (ru_RU, int8)",
        languageCode = "ru",
        url = RELEASE + "vits-piper-ru_RU-dmitri-medium-int8.tar.bz2",
        sha256 = "7636793307f634ce54c6e65528a91a61683114f1a6635a08caf64ba6c54e6a63",
        archiveBytes = 21_129_441,
        rootDir = "vits-piper-ru_RU-dmitri-medium-int8",
        modelFile = "ru_RU-dmitri-medium.onnx"
    )

    val ALL = listOf(RU_IRINA_INT8, RU_DMITRI_INT8)
    val DEFAULT_RU = RU_IRINA_INT8

    fun byId(id: String): TtsVoice? = ALL.firstOrNull { it.id == id }
}

/** Paths sherpa-onnx needs to load an installed Piper voice. */
data class InstalledTtsVoice(val model: File, val tokens: File, val espeakDataDir: File)

/**
 * Downloads (on demand, never bundled), verifies (SHA-256, enforced) and
 * extracts Piper voices into `<root>/<voice id>/`. Downloads are serialized
 * by a mutex and use unique temp names, so two buttons can never race.
 */
@Singleton
class TtsVoiceManager internal constructor(private val rootProvider: () -> File) {

    @Inject constructor(@ApplicationContext context: Context) :
        this({ File(context.filesDir, "tts") })

    private val rootDir: File by lazy { rootProvider().apply { mkdirs() } }
    private val mutex = Mutex()

    private fun voiceDir(voice: TtsVoice) = File(rootDir, voice.id)

    fun installed(voice: TtsVoice): InstalledTtsVoice? {
        val dir = voiceDir(voice)
        return if (File(dir, MARKER).isFile) contentsOf(dir, voice) else null
    }

    /** Required files present (checked before the marker is written, and on read). */
    private fun contentsOf(dir: File, voice: TtsVoice): InstalledTtsVoice? {
        val model = File(dir, voice.modelFile)
        val tokens = File(dir, "tokens.txt")
        val espeak = File(dir, "espeak-ng-data")
        val ok = model.length() > 1_000_000 && tokens.isFile && File(espeak, "phontab").isFile
        return if (ok) InstalledTtsVoice(model, tokens, espeak) else null
    }

    fun isInstalled(voice: TtsVoice): Boolean = installed(voice) != null

    fun installedSizeBytes(voice: TtsVoice): Long =
        voiceDir(voice).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun download(
        voice: TtsVoice,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (isInstalled(voice)) return@withContext
            val archive = VerifiedDownload.fetchVerified(
                url = voice.url,
                dir = rootDir,
                prefix = voice.id,
                expectedSha256 = voice.sha256,
                expectedSize = voice.archiveBytes,
                onProgress = onProgress
            )
            try {
                install(archive, voice)
                Timber.i("TtsVoiceManager: installed ${voice.id} (${installedSizeBytes(voice)} bytes)")
            } finally {
                archive.delete()
            }
        }
    }

    /** Extracts a verified archive and publishes it; the marker is the very last step. */
    internal fun install(archive: File, voice: TtsVoice) {
        val staging = File(rootDir, "${voice.id}.staging")
        try {
            SafeTarBz2.extract(archive, staging, voice.rootDir.trimEnd('/') + "/")
            if (contentsOf(staging, voice) == null) {
                throw IOException("Voice archive ${voice.id} is missing model/tokens/espeak-ng-data")
            }
            val dir = voiceDir(voice)
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) {
                staging.copyRecursively(dir, overwrite = true)
            }
            File(dir, MARKER).writeText(voice.sha256)
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun delete(voice: TtsVoice) = mutex.withLock {
        withContext(Dispatchers.IO) { voiceDir(voice).deleteRecursively() }
    }

    private companion object {
        const val MARKER = ".installed"
    }
}
