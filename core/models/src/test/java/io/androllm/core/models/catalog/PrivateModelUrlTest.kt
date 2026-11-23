package io.androllm.core.models.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Branch F: private, content-addressed tailnet model URLs. */
class PrivateModelUrlTest {

    private val sha = "0123456789abcdef".repeat(4)
    private val url = "tailnet://androllm-models/$sha/qwen3_4b_2507_f-4b-v1.litertlm"

    private fun model(downloadUrl: String = url, sha256: String = sha) = CatalogModel(
        id = "jarvis-f", name = "Jarvis F", fileName = "qwen3_4b_2507_f-4b-v1.litertlm",
        downloadUrl = downloadUrl, sha256 = sha256,
    )

    @Test
    fun resolvesAgainstHost() {
        assertEquals("http://100.64.0.10:8806/$sha/qwen3_4b_2507_f-4b-v1.litertlm", PrivateModelUrl.resolve(url, "100.64.0.10:8806"))
        assertEquals("http://pi.example.ts.net:8806/$sha/qwen3_4b_2507_f-4b-v1.litertlm", PrivateModelUrl.resolve(url, " pi.example.ts.net:8806 "))
    }

    @Test
    fun nonPrivateUrlPassesThrough() {
        val hf = "https://huggingface.co/a/b/resolve/${"a".repeat(40)}/f.litertlm"
        assertEquals(hf, PrivateModelUrl.resolve(hf, "x:1"))
        assertFalse(PrivateModelUrl.isPrivate(hf))
    }

    @Test
    fun malformedPrivateUrlOrHostIsRejected() {
        assertNull(PrivateModelUrl.resolve("tailnet://androllm-models/abc/f.litertlm", "h:1"))
        assertNull(PrivateModelUrl.resolve("tailnet://other/$sha/f.litertlm", "h:1"))
        assertNull(PrivateModelUrl.resolve("tailnet://androllm-models/$sha/../etc/passwd", "h:1"))
        assertNull(PrivateModelUrl.resolve(url, "h/evil"))
        assertNull(PrivateModelUrl.resolve(url, "user@h:1"))
    }

    @Test
    fun pinPolicyAcceptsMatchingShaOnly() {
        assertTrue(CatalogPinPolicy.isPinned(model()))
        assertNotNull(CatalogPinPolicy.problem(model(sha256 = "f".repeat(64))))
        assertNotNull(CatalogPinPolicy.problem(model(downloadUrl = "tailnet://androllm-models/short/f.litertlm")))
    }

    @Test
    fun validatorAllowsPrivateSchemeButStillRejectsPlainHttp() {
        val ok = CatalogValidator.validate(listOf(model()))
        assertFalse(ok.errors.any { it.contains("https") })
        val bad = CatalogValidator.validate(listOf(model(downloadUrl = "http://100.64.0.10:8806/$sha/f.litertlm")))
        assertTrue(bad.errors.any { it.contains("https") })
    }
}
