package io.androllm.core.jarvislink

import io.androllm.core.jarvislink.protocol.JarvisProtocol

/** Builds encrypted task envelopes for tests (same construction as jarvis-phone-task). */
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
        key: ByteArray = KEY
    ): ByteArray = JarvisProtocol.encodeTask(key, id, text, ts, ttl, from)
}
