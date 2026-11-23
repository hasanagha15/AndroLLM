package io.androllm.engine.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

class NpuLibraryDirTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    private fun src(name: String, data: ByteArray, size: Long = data.size.toLong(), crc: Long = crc(data)) =
        NpuLibraryDir.Source(name, size, crc) { ByteArrayInputStream(data) }

    private val a = ByteArray(200_000) { (it % 251).toByte() }
    private val b = "skel-bytes".toByteArray()

    @Test
    fun `stages and verifies and writes stamp then reuses on second call`() {
        val dir = File(tmp.root, "npu-libs")
        val sources = listOf(src("libA.so", a), src("libSkel.so", b, size = -1, crc = -1))
        assertEquals(2, NpuLibraryDir.stageInto(dir, "9|100|/apk", sources))
        assertTrue(File(dir, "libA.so").readBytes().contentEquals(a))
        assertTrue(File(dir, "libSkel.so").readBytes().contentEquals(b))
        assertTrue(File(dir, NpuLibraryDir.STAMP).isFile)
        assertEquals(0, NpuLibraryDir.stageInto(dir, "9|100|/apk", sources))
    }

    @Test
    fun `new versionCode or truncated file forces a re-copy`() {
        val dir = File(tmp.root, "npu-libs")
        val sources = listOf(src("libA.so", a))
        NpuLibraryDir.stageInto(dir, "9|100|/apk", sources)
        assertEquals(1, NpuLibraryDir.stageInto(dir, "10|200|/apk", sources))
        // Simulate a zero-length file after power loss.
        File(dir, "libA.so").apply { setWritable(true); writeBytes(ByteArray(0)) }
        assertFalse(NpuLibraryDir.isStampValid(dir, "10|200|/apk", sources))
        assertEquals(1, NpuLibraryDir.stageInto(dir, "10|200|/apk", sources))
        assertEquals(a.size.toLong(), File(dir, "libA.so").length())
    }

    @Test
    fun `stale libs and tmp leftovers are removed`() {
        val dir = File(tmp.root, "npu-libs").apply { mkdirs() }
        File(dir, "libOld.so").writeText("old")
        File(dir, "libA.so.tmp").writeText("partial")
        NpuLibraryDir.stageInto(dir, "9|1|/apk", listOf(src("libA.so", a)))
        assertEquals(setOf("libA.so", NpuLibraryDir.STAMP), dir.list()!!.toSet())
    }

    @Test
    fun `crc or size mismatch fails without leaving a file or stamp then retry succeeds`() {
        val dir = File(tmp.root, "npu-libs")
        try {
            NpuLibraryDir.stageInto(dir, "9|1|/apk", listOf(src("libA.so", a, crc = crc(a) xor 1)))
            fail("expected crc mismatch")
        } catch (_: IOException) { }
        try {
            NpuLibraryDir.stageInto(dir, "9|1|/apk", listOf(src("libA.so", a, size = a.size + 1L)))
            fail("expected size mismatch")
        } catch (_: IOException) { }
        assertFalse(File(dir, "libA.so").exists())
        assertFalse(File(dir, "libA.so.tmp").exists())
        assertFalse(File(dir, NpuLibraryDir.STAMP).exists())
        assertEquals(1, NpuLibraryDir.stageInto(dir, "9|1|/apk", listOf(src("libA.so", a))))
    }

    @Test
    fun `qualcomm detection`() {
        val no = { false }
        // Android 12+: SOC_MANUFACTURER is authoritative.
        assertTrue(NpuLibraryDir.isQualcommSoc(34, "QTI", "qcom", "sun", no))
        assertFalse(NpuLibraryDir.isQualcommSoc(34, "Samsung", "s5e9945", "s5e9945", { true }))
        assertFalse(NpuLibraryDir.isQualcommSoc(31, "Mediatek", "mt6983", "mt6983", no))
        // Android 9–11: heuristics, default false.
        assertTrue(NpuLibraryDir.isQualcommSoc(30, null, "qcom", "kona", no))
        assertTrue(NpuLibraryDir.isQualcommSoc(29, null, "unknown", "msmnile", no))
        assertTrue(NpuLibraryDir.isQualcommSoc(28, null, "x", "sdm845", no))
        assertTrue(NpuLibraryDir.isQualcommSoc(30, null, "x", "y", { true }))
        assertFalse(NpuLibraryDir.isQualcommSoc(30, null, "exynos990", "universal990", no))
        assertFalse(NpuLibraryDir.isQualcommSoc(29, null, "mt6785", "mt6785", no))
        assertFalse(NpuLibraryDir.isQualcommSoc(28, null, null, null, { throw SecurityException() }))
    }
}
