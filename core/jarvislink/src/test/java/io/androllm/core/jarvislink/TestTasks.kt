package io.androllm.core.jarvislink

import io.androllm.core.jarvislink.protocol.JarvisProtocol
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds signed task payloads for tests (same canonical form as jarvis-phone-task). */
object TestTasks {
    const val KEY_HEX = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    val KEY: ByteArray = JarvisProtocol.parseKeyHex(KEY_HEX)!!
    val OTHER_KEY: ByteArray = JarvisProtocol.parseKeyHex("ff".repeat(32))!!

    fun signed(
        id: String,
        text: String = "hello",
        ts: Long,
        ttl: Long = 600,
        from: String = "jarvis",
        key: ByteArray = KEY,
        tamperText: String? = null,
        omitSig: Boolean = false,
        omitTs: Boolean = false
    ): ByteArray {
        val sig = JarvisProtocol.hmacHex(key, JarvisProtocol.taskSigningInput(id, ts, ttl, from, text))
        return buildJsonObject {
            put("v", 1)
            put("id", id)
            put("text", tamperText ?: text)
            put("from", from)
            if (!omitTs) put("ts", JsonPrimitive(ts))
            put("ttl_s", JsonPrimitive(ttl))
            if (!omitSig) put("sig", sig)
        }.toString().toByteArray()
    }
}
