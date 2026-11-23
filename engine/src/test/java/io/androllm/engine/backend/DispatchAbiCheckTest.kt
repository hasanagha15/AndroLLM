package io.androllm.engine.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [DispatchAbiCheck] on synthetic ELF64 files (a `.dynsym` + `.dynstr`) and
 * the probe gates built on it (M1 of the H gate review).
 */
class DispatchAbiCheckTest {

    /** Minimal ELF64 LE: null section, .dynstr, .dynsym with the given defined globals (+1 undefined). */
    private fun elf(defined: List<String>, undefined: List<String> = emptyList()): ByteArray {
        val names = defined + undefined
        val str = java.io.ByteArrayOutputStream().apply { write(0) }
        val offsets = names.map { n -> str.size().also { str.write(n.toByteArray()); str.write(0) } }
        val strBytes = str.toByteArray()
        val symCount = 1 + names.size
        val headerSize = 64
        val strOff = headerSize
        val symOff = strOff + strBytes.size + 8 - (strBytes.size % 8)
        val symSize = symCount * 24
        val shOff = symOff + symSize
        val total = shOff + 3 * 64
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 0x7f); b.put(1, 'E'.code.toByte()); b.put(2, 'L'.code.toByte()); b.put(3, 'F'.code.toByte())
        b.put(4, 2); b.put(5, 1); b.put(6, 1)
        b.putLong(0x28, shOff.toLong())
        b.putShort(0x3A, 64); b.putShort(0x3C, 3); b.putShort(0x3E, 0)
        for (i in strBytes.indices) b.put(strOff + i, strBytes[i])
        names.forEachIndexed { i, _ ->
            val p = symOff + (i + 1) * 24
            b.putInt(p, offsets[i])
            b.put(p + 4, ((1 shl 4) or 2).toByte()) // GLOBAL FUNC
            b.putShort(p + 6, if (i < defined.size) 7 else 0) // defined vs UND
        }
        // section 1: .dynstr (STRTAB)
        val s1 = shOff + 64
        b.putInt(s1 + 4, 3); b.putLong(s1 + 0x18, strOff.toLong()); b.putLong(s1 + 0x20, strBytes.size.toLong())
        // section 2: .dynsym
        val s2 = shOff + 128
        b.putInt(s2 + 4, 11); b.putLong(s2 + 0x18, symOff.toLong()); b.putLong(s2 + 0x20, symSize.toLong())
        b.putInt(s2 + 0x28, 1); b.putLong(s2 + 0x38, 24)
        return b.array()
    }

    @Test
    fun `new-ABI dispatcher exporting QueryInterface is supported`() {
        val so = elf(listOf("LiteRtDispatchQueryInterface"))
        assertEquals(DispatchAbiCheck.Result.SUPPORTED, DispatchAbiCheck.check(so))
    }

    @Test
    fun `legacy 2_2_0 dispatcher exporting only GetApi is rejected`() {
        val so = elf(listOf("LiteRtDispatchGetApi"))
        assertEquals(DispatchAbiCheck.Result.LEGACY_ABI, DispatchAbiCheck.check(so))
    }

    @Test
    fun `an undefined import of the symbol does not count as an export`() {
        val so = elf(listOf("LiteRtDispatchGetApi"), undefined = listOf("LiteRtDispatchQueryInterface"))
        assertEquals(setOf("LiteRtDispatchGetApi"), DispatchAbiCheck.dynamicSymbols(so))
        assertEquals(DispatchAbiCheck.Result.LEGACY_ABI, DispatchAbiCheck.check(so))
    }

    @Test
    fun `garbage or missing bytes are unreadable`() {
        assertEquals(DispatchAbiCheck.Result.UNREADABLE, DispatchAbiCheck.check(null))
        assertEquals(DispatchAbiCheck.Result.UNREADABLE, DispatchAbiCheck.check(ByteArray(100)))
        assertEquals(DispatchAbiCheck.Result.UNREADABLE, DispatchAbiCheck.check(elf(listOf("x")).copyOf(80)))
    }

    private val qcomProps = mapOf("ro.soc.manufacturer" to "QTI", "ro.soc.model" to "SM8850")
    private val dispatch = listOf("libLiteRtDispatch_Qualcomm.so")

    @Test
    fun `probe disables NPU when the dispatcher has the legacy ABI`() {
        val caps = HardwareBackendProbe.fromProperties(
            qcomProps, dispatchLibraries = dispatch,
            dispatchAbi = DispatchAbiCheck.Result.LEGACY_ABI, bundledHexagonArchs = setOf("V81")
        )
        assertTrue(caps.npuAvailable)
        assertFalse(caps.npuUsable)
    }

    @Test
    fun `probe disables NPU when the dispatcher could not be read`() {
        val caps = HardwareBackendProbe.fromProperties(
            qcomProps, dispatchLibraries = dispatch,
            dispatchAbi = DispatchAbiCheck.Result.UNREADABLE, bundledHexagonArchs = setOf("V81")
        )
        assertFalse(caps.npuUsable)
    }

    @Test
    fun `probe keeps NPU for a supported dispatcher on a V81 SoC`() {
        val caps = HardwareBackendProbe.fromProperties(
            qcomProps, dispatchLibraries = dispatch,
            dispatchAbi = DispatchAbiCheck.Result.SUPPORTED, bundledHexagonArchs = setOf("V81")
        )
        assertTrue(caps.npuUsable)
    }

    @Test
    fun `S24 Ultra SM8650 V75 is not usable with only the V81 skel bundled`() {
        val caps = HardwareBackendProbe.fromProperties(
            mapOf("ro.soc.manufacturer" to "QTI", "ro.soc.model" to "SM8650"),
            dispatchLibraries = dispatch,
            dispatchAbi = DispatchAbiCheck.Result.SUPPORTED, bundledHexagonArchs = setOf("V81")
        )
        assertTrue(caps.npuAvailable)
        assertFalse(caps.npuUsable)
    }

    @Test
    fun `unknown SoC model is not blocked by the arch gate`() {
        val caps = HardwareBackendProbe.fromProperties(
            mapOf("ro.soc.manufacturer" to "QTI", "ro.soc.model" to "SM9999"),
            dispatchLibraries = dispatch,
            dispatchAbi = DispatchAbiCheck.Result.SUPPORTED, bundledHexagonArchs = setOf("V81")
        )
        assertTrue(caps.npuUsable)
    }

    @Test
    fun `bundled skel archs come from the staged skel name`() {
        assertEquals(setOf("V81"), HardwareBackendProbe.bundledHexagonArchs())
        assertEquals("V75", HardwareBackendProbe.hexagonArchFor("sm8650"))
    }
}
