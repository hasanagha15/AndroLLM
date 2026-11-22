package io.androllm.core.voice.download

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Thrown when downloaded bytes do not match the pinned SHA-256 / size. */
class IntegrityException(message: String) : IOException(message)

/**
 * Network + integrity primitives shared by the whisper model and TTS voice
 * managers. Pure JVM (no Android types) so they are unit-tested.
 */
object VerifiedDownload {

    /** Opens [url] (redirects followed) and fails on non-2xx. Replaceable in tests. */
    @Volatile
    var opener: (String) -> InputStream = { url ->
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "AndroLLM/3.0")
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code for ${url.substringAfterLast('/')}")
        }
        DisconnectingInputStream(conn)
    }

    /**
     * Streams [url] into a uniquely named temp file inside [dir], checking
     * the byte count while reading (aborts as soon as it exceeds
     * [expectedSize]) and the SHA-256 at the end. Returns the verified temp
     * file; the caller publishes it (rename). On any failure the temp file
     * is deleted and the exception rethrown — nothing partial stays on disk.
     */
    fun fetchVerified(
        url: String,
        dir: File,
        prefix: String,
        expectedSha256: String,
        expectedSize: Long,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        dir.mkdirs()
        val tmp = File.createTempFile("dl-$prefix.", ".part", dir)
        var ok = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPct = -1
            val buf = ByteArray(64 * 1024)
            opener(url).use { input ->
                FileOutputStream(tmp).use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > expectedSize) {
                            throw IntegrityException("Download of $prefix exceeds expected $expectedSize bytes")
                        }
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        val pct = ((done * 100) / expectedSize.coerceAtLeast(1)).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(done, expectedSize)
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (done != expectedSize || !actual.equals(expectedSha256, ignoreCase = true)) {
                throw IntegrityException(
                    "Checksum mismatch for $prefix: expected $expectedSha256 ($expectedSize B), got $actual ($done B)"
                )
            }
            ok = true
            return tmp
        } finally {
            if (!ok) tmp.delete()
        }
    }

    private class DisconnectingInputStream(private val conn: HttpURLConnection) :
        java.io.FilterInputStream(conn.inputStream) {
        override fun close() {
            try { super.close() } finally { conn.disconnect() }
        }
    }
}

/** Limits for [SafeTarBz2.extract]. */
data class TarLimits(
    val maxEntries: Int = 2_000,
    val maxEntryBytes: Long = 150L * 1024 * 1024,
    val maxTotalBytes: Long = 200L * 1024 * 1024
)

/**
 * tar.bz2 extraction for downloaded voice archives: only entries under
 * [rootPrefix] are extracted; any link/device/other entry type aborts; entry
 * count and sizes are bounded; every target is canonical-path checked.
 * On failure [staging] is removed.
 */
object SafeTarBz2 {

    fun extract(archive: File, staging: File, rootPrefix: String, limits: TarLimits = TarLimits()) {
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingCanon = staging.canonicalFile
        var ok = false
        try {
            var entries = 0
            var total = 0L
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (++entries > limits.maxEntries) throw IOException("Archive has too many entries")
                    val regularFile = entry.isFile && !entry.isSymbolicLink && !entry.isLink
                    if (!entry.isDirectory && !regularFile) {
                        throw IOException("Unsupported tar entry type (link/device): ${entry.name}")
                    }
                    val rel = TarPaths.relativeInside(entry.name, rootPrefix) ?: continue
                    val target = File(staging, rel)
                    val canon = target.canonicalFile
                    if (!canon.path.startsWith(stagingCanon.path + File.separator)) {
                        throw IOException("Tar entry escapes target dir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        canon.mkdirs()
                        continue
                    }
                    if (entry.size > limits.maxEntryBytes) throw IOException("Tar entry too large: ${entry.name}")
                    canon.parentFile?.mkdirs()
                    var written = 0L
                    val buf = ByteArray(64 * 1024)
                    FileOutputStream(canon).use { out ->
                        while (true) {
                            val n = tar.read(buf)
                            if (n < 0) break
                            written += n
                            total += n
                            if (written > limits.maxEntryBytes) throw IOException("Tar entry too large: ${entry.name}")
                            if (total > limits.maxTotalBytes) throw IOException("Archive expands beyond ${limits.maxTotalBytes} bytes")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            ok = true
        } finally {
            if (!ok) staging.deleteRecursively()
        }
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
        if (rel.split('/').any { it == ".." || it.isEmpty() }) return null
        return rel
    }
}
