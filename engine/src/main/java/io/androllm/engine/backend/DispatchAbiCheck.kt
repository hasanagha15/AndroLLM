package io.androllm.engine.backend

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Checks whether a LiteRT vendor dispatch library (`libLiteRtDispatch_*.so`)
 * speaks the dispatch ABI of the bundled LiteRT-LM runtime, by reading its
 * ELF dynamic symbol table — no dlopen, so a mismatched library is never
 * loaded into the process.
 *
 * LiteRT-LM 0.19.1 (`liblitertlm_jni.so`) resolves the vendor entry point
 * [REQUIRED_SYMBOL] (`LiteRtDispatchQueryInterface`) and rejects the legacy
 * `LiteRtDispatchInitialize`; the LiteRT 2.2.0-era dispatchers export only
 * `LiteRtDispatchGetApi`, which 0.19.1 no longer looks up. With such a
 * library NPU initialization can never succeed, so the probe must not offer
 * NPU (AUTO would otherwise try it first for `supportsNpu` models).
 */
object DispatchAbiCheck {

    /** Entry point LiteRT-LM 0.19.x resolves in the vendor dispatch library. */
    const val REQUIRED_SYMBOL = "LiteRtDispatchQueryInterface"

    /** Result of a symbol check. */
    enum class Result { SUPPORTED, LEGACY_ABI, UNREADABLE }

    fun check(elf: ByteArray?): Result {
        if (elf == null) return Result.UNREADABLE
        val symbols = runCatching { dynamicSymbols(elf) }.getOrNull() ?: return Result.UNREADABLE
        return if (REQUIRED_SYMBOL in symbols) Result.SUPPORTED else Result.LEGACY_ABI
    }

    /**
     * Names of the defined global/weak symbols in `.dynsym` of a 64-bit
     * little-endian ELF. Throws on malformed input.
     */
    fun dynamicSymbols(elf: ByteArray): Set<String> {
        require(elf.size >= 64) { "too small" }
        require(elf[0] == 0x7f.toByte() && elf[1] == 'E'.code.toByte() &&
            elf[2] == 'L'.code.toByte() && elf[3] == 'F'.code.toByte()) { "not ELF" }
        require(elf[4].toInt() == 2) { "not ELF64" }
        require(elf[5].toInt() == 1) { "not little-endian" }
        val b = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
        val shoff = b.getLong(0x28)
        val shentsize = b.getShort(0x3A).toInt() and 0xFFFF
        val shnum = b.getShort(0x3C).toInt() and 0xFFFF
        require(shoff > 0 && shentsize >= 64 && shoff + shentsize.toLong() * shnum <= elf.size) { "bad section table" }
        val out = mutableSetOf<String>()
        for (i in 0 until shnum) {
            val sh = (shoff + i.toLong() * shentsize).toInt()
            val type = b.getInt(sh + 4)
            if (type != SHT_DYNSYM) continue
            val symOff = b.getLong(sh + 0x18)
            val symSize = b.getLong(sh + 0x20)
            val link = b.getInt(sh + 0x28)
            val entSize = b.getLong(sh + 0x38).takeIf { it > 0 } ?: 24L
            require(link in 0 until shnum) { "bad sh_link" }
            val strSh = (shoff + link.toLong() * shentsize).toInt()
            val strOff = b.getLong(strSh + 0x18)
            val strSize = b.getLong(strSh + 0x20)
            require(symOff + symSize <= elf.size && strOff + strSize <= elf.size) { "section out of bounds" }
            var p = symOff
            while (p + entSize <= symOff + symSize) {
                val nameIdx = b.getInt(p.toInt()).toLong() and 0xFFFFFFFFL
                val info = elf[(p + 4).toInt()].toInt() and 0xFF
                val shndx = b.getShort((p + 6).toInt()).toInt() and 0xFFFF
                val bind = info shr 4
                if (shndx != 0 && (bind == STB_GLOBAL || bind == STB_WEAK) && nameIdx < strSize) {
                    val start = (strOff + nameIdx).toInt()
                    var end = start
                    val limit = (strOff + strSize).toInt()
                    while (end < limit && elf[end] != 0.toByte()) end++
                    out += String(elf, start, end - start, Charsets.US_ASCII)
                }
                p += entSize
            }
        }
        return out
    }

    private const val SHT_DYNSYM = 11
    private const val STB_GLOBAL = 1
    private const val STB_WEAK = 2
}
