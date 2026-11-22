package io.androllm.core.voice.download

import com.google.common.truth.Truth.assertThat
import io.androllm.core.voice.stt.WhisperModel
import io.androllm.core.voice.stt.WhisperModelManager
import io.androllm.core.voice.tts.TtsVoice
import io.androllm.core.voice.tts.TtsVoiceManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** Integrity + extraction behaviour (gate Н1–Н4) with the network replaced in-process. */
class DownloadAndExtractTest {

    private lateinit var tmp: File
    private val realOpener = VerifiedDownload.opener
    private var connections = 0

    @Before fun setUp() {
        tmp = Files.createTempDirectory("voice-dl-test").toFile()
    }

    @After fun tearDown() {
        VerifiedDownload.opener = realOpener
        tmp.deleteRecursively()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun serve(bytes: ByteArray) {
        VerifiedDownload.opener = { connections++; ByteArrayInputStream(bytes) }
    }

    private fun serveFailingAfter(bytes: ByteArray, failAt: Int) {
        VerifiedDownload.opener = {
            connections++
            object : InputStream() {
                var i = 0
                override fun read(): Int {
                    if (i >= failAt) throw IOException("connection reset")
                    return if (i < bytes.size) bytes[i++].toInt() and 0xff else -1
                }
            }
        }
    }

    // ── VerifiedDownload ─────────────────────────────────────────────────────

    @Test fun `good bytes are returned as verified temp file`() {
        val data = Random(1).nextBytes(200_000)
        serve(data)
        val f = VerifiedDownload.fetchVerified("u", tmp, "m", sha(data), data.size.toLong())
        assertThat(f.readBytes()).isEqualTo(data)
        assertThat(f.name).endsWith(".part")
    }

    @Test fun `wrong hash same size is rejected and leaves nothing`() {
        val data = Random(2).nextBytes(100_000)
        val tampered = data.copyOf().also { it[500] = (it[500].toInt() xor 1).toByte() }
        serve(tampered)
        assertThrows(IntegrityException::class.java) {
            VerifiedDownload.fetchVerified("u", tmp, "m", sha(data), data.size.toLong())
        }
        assertThat(tmp.listFiles()!!.toList()).isEmpty()
    }

    @Test fun `short and long downloads are rejected`() {
        val data = Random(3).nextBytes(100_000)
        serve(data.copyOf(90_000))
        assertThrows(IntegrityException::class.java) {
            VerifiedDownload.fetchVerified("u", tmp, "m", sha(data), data.size.toLong())
        }
        serve(data + ByteArray(10))
        assertThrows(IntegrityException::class.java) {
            VerifiedDownload.fetchVerified("u", tmp, "m", sha(data), data.size.toLong())
        }
        assertThat(tmp.listFiles()!!.toList()).isEmpty()
    }

    @Test fun `network failure mid-stream deletes the temp file`() {
        val data = Random(4).nextBytes(100_000)
        serveFailingAfter(data, 70_000)
        assertThrows(IOException::class.java) {
            VerifiedDownload.fetchVerified("u", tmp, "m", sha(data), data.size.toLong())
        }
        assertThat(tmp.listFiles()!!.toList()).isEmpty()
    }

    // ── WhisperModelManager ──────────────────────────────────────────────────

    private fun fakeModel(data: ByteArray, hash: String = sha(data)) = WhisperModel(
        id = "fake", fileName = "ggml-fake.bin", displayName = "Fake", sizeBytes = data.size.toLong(),
        sha256 = hash, multilingual = true, tier = 0
    )

    @Test fun `whisper manager never installs a bad model and installs a good one`() = runBlocking<Unit> {
        val dir = File(tmp, "whisper")
        val mgr = WhisperModelManager { dir }
        val data = Random(5).nextBytes(150_000)
        val model = fakeModel(data)

        serve(data.reversedArray())
        assertThrows(IntegrityException::class.java) { runBlocking { mgr.download(model) } }
        assertThat(mgr.isInstalled(model)).isFalse()
        assertThat(dir.listFiles()!!.toList()).isEmpty()

        serve(data)
        mgr.download(model)
        assertThat(mgr.isInstalled(model)).isTrue()
        assertThat(File(dir, "ggml-fake.bin").readBytes()).isEqualTo(data)
        assertThat(dir.listFiles()!!.map { it.name }).containsExactly("ggml-fake.bin")

        val before = connections
        mgr.download(model) // already installed: no connection
        assertThat(connections).isEqualTo(before)
    }

    // ── tar.bz2 extraction ───────────────────────────────────────────────────

    private val root = "vits-piper-test"
    private val modelBytes = Random(6).nextBytes(1_100_000)

    private sealed class E {
        data class F(val name: String, val data: ByteArray) : E()
        data class D(val name: String) : E()
        data class Sym(val name: String, val target: String) : E()
    }

    private fun tarBz2(entries: List<E>): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bos)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            entries.forEach { e ->
                when (e) {
                    is E.D -> { tar.putArchiveEntry(TarArchiveEntry("${e.name}/")); tar.closeArchiveEntry() }
                    is E.F -> {
                        val te = TarArchiveEntry(e.name).apply { size = e.data.size.toLong() }
                        tar.putArchiveEntry(te); tar.write(e.data); tar.closeArchiveEntry()
                    }
                    is E.Sym -> {
                        val te = TarArchiveEntry(e.name, TarArchiveEntry.LF_SYMLINK).apply { linkName = e.target }
                        tar.putArchiveEntry(te); tar.closeArchiveEntry()
                    }
                }
            }
        }
        return bos.toByteArray()
    }

    private fun goodEntries() = listOf(
        E.D(root),
        E.F("$root/model.onnx", modelBytes),
        E.F("$root/tokens.txt", "a 1\n".toByteArray()),
        E.D("$root/espeak-ng-data"),
        E.F("$root/espeak-ng-data/phontab", ByteArray(100) { 1 })
    )

    private fun voice(archive: ByteArray) = TtsVoice(
        id = "test-voice", displayName = "Test", languageCode = "ru", url = "u", sha256 = sha(archive),
        archiveBytes = archive.size.toLong(), rootDir = root, modelFile = "model.onnx"
    )

    @Test fun `good voice archive installs with marker`() = runBlocking<Unit> {
        val archive = tarBz2(goodEntries())
        val mgr = TtsVoiceManager { File(tmp, "tts") }
        serve(archive)
        mgr.download(voice(archive))
        assertThat(mgr.isInstalled(voice(archive))).isTrue()
        assertThat(File(tmp, "tts").listFiles()!!.map { it.name }).containsExactly("test-voice")
    }

    @Test fun `voice archive with wrong sha is not extracted`() = runBlocking<Unit> {
        val archive = tarBz2(goodEntries())
        val v = voice(archive).copy(sha256 = "0".repeat(64))
        val mgr = TtsVoiceManager { File(tmp, "tts") }
        serve(archive)
        assertThrows(IntegrityException::class.java) { runBlocking { mgr.download(v) } }
        assertThat(mgr.isInstalled(v)).isFalse()
        assertThat(File(tmp, "tts").listFiles()!!.toList()).isEmpty()
    }

    @Test fun `traversal entries are dropped and outside dir untouched`() {
        val outside = File(tmp, "evil.txt")
        val archive = tarBz2(goodEntries() + listOf(
            E.F("$root/../evil.txt", "x".toByteArray()),
            E.F("other/evil.txt", "x".toByteArray()),
            E.F("/abs/evil.txt", "x".toByteArray())
        ))
        val a = File(tmp, "a.tar.bz2").apply { writeBytes(archive) }
        val staging = File(tmp, "staging")
        SafeTarBz2.extract(a, staging, "$root/")
        assertThat(outside.exists()).isFalse()
        assertThat(File(staging, "model.onnx").length()).isEqualTo(modelBytes.size.toLong())
    }

    @Test fun `symlink entry aborts install and cleans staging`() = runBlocking<Unit> {
        val archive = tarBz2(goodEntries() + E.Sym("$root/link", "/etc/passwd"))
        val mgr = TtsVoiceManager { File(tmp, "tts") }
        serve(archive)
        assertThrows(IOException::class.java) { runBlocking { mgr.download(voice(archive)) } }
        assertThat(mgr.isInstalled(voice(archive))).isFalse()
        assertThat(File(tmp, "tts").listFiles()!!.toList()).isEmpty()
    }

    @Test fun `archive missing the model is not marked installed`() = runBlocking<Unit> {
        val archive = tarBz2(goodEntries().filterNot { it is E.F && it.name.endsWith("model.onnx") })
        val mgr = TtsVoiceManager { File(tmp, "tts") }
        serve(archive)
        assertThrows(IOException::class.java) { runBlocking { mgr.download(voice(archive)) } }
        assertThat(mgr.isInstalled(voice(archive))).isFalse()
        assertThat(File(tmp, "tts").listFiles()!!.toList()).isEmpty()
    }

    @Test fun `decompression bomb is stopped by limits`() {
        val archive = tarBz2(listOf(E.D(root), E.F("$root/zeros", ByteArray(5_000_000))))
        val a = File(tmp, "bomb.tar.bz2").apply { writeBytes(archive) }
        val staging = File(tmp, "staging")
        assertThrows(IOException::class.java) {
            SafeTarBz2.extract(a, staging, "$root/", TarLimits(maxTotalBytes = 1_000_000, maxEntryBytes = 10_000_000))
        }
        assertThat(staging.exists()).isFalse()
    }
}
