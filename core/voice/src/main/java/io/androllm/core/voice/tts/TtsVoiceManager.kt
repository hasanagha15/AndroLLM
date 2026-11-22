package io.androllm.core.voice.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
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
 * extracts Piper voices into `filesDir/tts/<voice id>/`.
 */
@Singleton
class TtsVoiceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val rootDir: File by lazy { File(context.filesDir, "tts").apply { mkdirs() } }

    private fun voiceDir(voice: TtsVoice) = File(rootDir, voice.id)

    fun installed(voice: TtsVoice): InstalledTtsVoice? {
        val dir = voiceDir(voice)
        val model = File(dir, voice.modelFile)
        val tokens = File(dir, "tokens.txt")
        val espeak = File(dir, "espeak-ng-data")
        val ok = model.length() > 1_000_000 && tokens.isFile && File(espeak, "phontab").isFile &&
            File(dir, MARKER).isFile
        return if (ok) InstalledTtsVoice(model, tokens, espeak) else null
    }

    fun isInstalled(voice: TtsVoice): Boolean = installed(voice) != null

    fun installedSizeBytes(voice: TtsVoice): Long =
        voiceDir(voice).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun download(
        voice: TtsVoice,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        if (isInstalled(voice)) return@withContext
        val archive = File(rootDir, "${voice.id}.tar.bz2.part")
        archive.delete()
        try {
            val conn = URL(voice.url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "AndroLLM/3.0")
            conn.connect()
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} for ${voice.id}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: voice.archiveBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            val buf = ByteArray(64 * 1024)
            FileOutputStream(archive).use { out ->
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(voice.sha256, ignoreCase = true)) {
                error("SHA-256 mismatch for ${voice.id}: expected ${voice.sha256}, got $actual")
            }
            extract(archive, voice)
            Timber.i("TtsVoiceManager: installed ${voice.id} (${installedSizeBytes(voice)} bytes)")
        } finally {
            archive.delete()
        }
    }

    private fun extract(archive: File, voice: TtsVoice) {
        val staging = File(rootDir, "${voice.id}.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val prefix = voice.rootDir.trimEnd('/') + "/"
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val rel = TarPaths.relativeInside(entry.name, prefix) ?: continue
                val target = File(staging, rel)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else if (entry.isFile) {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { tar.copyTo(it) }
                } // symlinks / devices are ignored (none in the Piper tarballs)
            }
        }
        File(staging, MARKER).writeText(voice.sha256)
        val dir = voiceDir(voice)
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) {
            staging.copyRecursively(dir, overwrite = true)
            staging.deleteRecursively()
        }
    }

    suspend fun delete(voice: TtsVoice) = withContext(Dispatchers.IO) {
        voiceDir(voice).deleteRecursively()
    }

    private companion object {
        const val MARKER = ".installed"
    }
}

/** Tar path sanitising (unit-tested). */
object TarPaths {
    /**
     * Returns the entry path relative to [prefix] (the voice's top-level dir),
     * or null for entries outside it, the root itself, absolute paths or any
     * parent-directory traversal.
     */
    fun relativeInside(entryName: String, prefix: String): String? {
        val name = entryName.replace('\\', '/').removePrefix("./")
        if (!name.startsWith(prefix)) return null
        val rel = name.removePrefix(prefix).trimEnd('/')
        if (rel.isEmpty() || rel.startsWith("/")) return null
        if (rel.split('/').any { it == ".." }) return null
        return rel
    }
}
