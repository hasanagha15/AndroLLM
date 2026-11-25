package io.androllm.core.cloud.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** Branch Q: failed Gemini STT/TTS calls log the HTTP code + a short reason, never the body. */
class GeminiVoiceErrorReasonTest {

    @Test
    fun `api status is used and the message is dropped`() {
        val body = """{"error":{"code":400,"message":"Invalid input: Q7SPOKENTEXT","status":"INVALID_ARGUMENT"}}"""
        assertEquals("INVALID_ARGUMENT", GeminiVoiceClient.errorReason(body, "Bad Request"))
    }

    @Test
    fun `non-json body falls back to the http reason phrase`() {
        assertEquals("Bad Request", GeminiVoiceClient.errorReason("echo Q7SPOKENTEXT", "Bad Request"))
    }

    @Test
    fun `nothing usable gives a generic reason`() {
        assertEquals("error", GeminiVoiceClient.errorReason("""{"error":{"status":"Q7 text here"}}""", ""))
        assertEquals("error", GeminiVoiceClient.errorReason("", null))
    }
}
