package io.androllm.core.voice.stt

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Downloads, verifies, stores and deletes whisper.cpp models on device.
 *
 * Models live in `filesDir/whisper/<fileName>`; [WhisperSpeechRecognizer]
 * picks and (re)loads the right one before every recognition session.
 * Downloads are serialized by a mutex and stream into a unique temp file that
 * is deleted on any failure; only a SHA-256 + size verified file is published.
 */
@Singleton
class WhisperModelManager internal constructor(private val dirProvider: () -> File) {

    @Inject constructor(@ApplicationContext context: Context) :
        this({ File(context.filesDir, "whisper") })

    private val modelsDir: File by lazy { dirProvider().apply { mkdirs() } }
    private val mutex = Mutex()

    fun modelFile(model: WhisperModel): File = File(modelsDir, model.fileName)

    /** Models that are fully downloaded (published file with the exact size). */
    fun installedModels(): List<WhisperModel> = WhisperModels.ALL.filter { isInstalled(it) }

    fun isInstalled(model: WhisperModel): Boolean {
        val f = modelFile(model)
        return f.isFile && f.length() == model.sizeBytes
    }

    /** Total disk used by downloaded whisper models, in bytes. */
    fun totalInstalledSizeBytes(): Long =
        installedModels().sumOf { modelFile(it).length() }

    /** Total disk used by downloaded whisper models, in bytes (settings UI). */
    fun storageInstalledSizeBytes(): Long = totalInstalledSizeBytes()

    /** Free bytes on the app's filesystem. */
    fun freeBytes(): Long = modelsDir.freeSpace

    /**
     * Downloads [model] with progress, verifies SHA-256 + size, then
     * publishes it. Throws on network/corruption failure; nothing partial
     * stays on disk.
     */
    suspend fun download(
        model: WhisperModel,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (isInstalled(model)) return@withContext
            modelsDir.mkdirs()
            val free = freeBytes()
            if (free in 1 until model.sizeBytes + 64L * 1024 * 1024) {
                throw IOException("Not enough free space for ${model.displayName}: need ${model.sizeBytes} B, free $free B")
            }
            val tmp = VerifiedDownload.fetchVerified(
                url = model.downloadUrl,
                dir = modelsDir,
                prefix = model.fileName,
                expectedSha256 = model.sha256,
                expectedSize = model.sizeBytes,
                onProgress = onProgress
            )
            val finalFile = modelFile(model)
            try {
                if (!tmp.renameTo(finalFile)) {
                    tmp.copyTo(finalFile, overwrite = true)
                }
            } finally {
                tmp.delete()
            }
            Timber.i("WhisperModels: installed ${model.fileName}: ${finalFile.length()} bytes")
        }
    }

    /** Deletes a downloaded model file. */
    suspend fun delete(model: WhisperModel) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = modelFile(model)
            if (f.exists() && !f.delete()) Timber.w("WhisperModels: failed to delete ${f.name}")
        }
    }
}
