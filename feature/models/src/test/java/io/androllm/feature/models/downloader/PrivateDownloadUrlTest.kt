package io.androllm.feature.models.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Branch F: tailnet:// catalog URLs become plain http on the owner's Tailscale host. */
class PrivateDownloadUrlTest {

    private val sha = "ab".repeat(32)

    @Test
    fun `private url resolves and becomes a valid download url`() {
        val r = resolveDownloadUrl("tailnet://androllm-models/$sha/m.litertlm", "100.64.0.10:8806")
        assertEquals("http://100.64.0.10:8806/$sha/m.litertlm", r)
        assertTrue(isValidDownloadUrl(r))
    }

    @Test
    fun `malformed private url stays invalid`() {
        val r = resolveDownloadUrl("tailnet://androllm-models/nothex/m.litertlm", "100.64.0.10:8806")
        assertFalse(isValidDownloadUrl(r))
    }

    @Test
    fun `huggingface url is untouched`() {
        val hf = "https://huggingface.co/a/b/resolve/${"c".repeat(40)}/m.litertlm"
        assertEquals(hf, resolveDownloadUrl(hf, "100.64.0.10:8806"))
    }

    @Test
    fun `default host comes from build config`() {
        // The placeholder (or the local.properties host) is a syntactically valid host.
        val r = resolveDownloadUrl("tailnet://androllm-models/$sha/m.litertlm")
        assertTrue(isValidDownloadUrl(r))
    }
}
