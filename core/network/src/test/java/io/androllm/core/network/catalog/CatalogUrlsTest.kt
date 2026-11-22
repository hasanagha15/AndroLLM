package io.androllm.core.network.catalog

import io.androllm.core.network.catalog.HfCatalogRemoteSource.FetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fork policy: the catalog is refreshed only from the owner's fork, newest valid copy wins. */
class CatalogUrlsTest {

    @Test
    fun refreshUsesOnlyForkBranchesIncludingMain() {
        val urls = HfCatalogRemoteSource.CATALOG_URLS
        assertEquals(3, urls.size)
        urls.forEach { url ->
            assertTrue(url, url.startsWith("https://raw.githubusercontent.com/hasanagha15/AndroLLM/refs/heads/"))
            assertTrue(url, url.endsWith("/core/models/src/main/assets/catalog_v1.json"))
        }
        assertTrue(urls.any { it.contains("/refs/heads/main/") })
        assertTrue(urls.any { it.contains("/refs/heads/jarvis/d-models/") })
    }

    @Test
    fun upstreamHostsAreGone() {
        val joined = HfCatalogRemoteSource.CATALOG_URLS.joinToString(" ")
        assertTrue(!joined.contains("models.androllm.com"))
        assertTrue(!joined.contains("ShadowSafin"))
    }

    // ---- selection ----

    private val now = 1_800_000_000_000L
    private val pin = "0123456789abcdef0123456789abcdef01234567"

    private fun model(id: String, revision: String = pin) =
        """{"id":"$id","name":"M","family":"Qwen","architecture":"qwen3","categories":["CHAT"],""" +
            """"license":"Apache-2.0","repoId":"t/m","revision":"$revision","fileName":"m.litertlm",""" +
            """"downloadUrl":"https://huggingface.co/t/m/resolve/$revision/m.litertlm","sizeBytes":1,""" +
            """"parameters":"1B","quantization":"Q4","version":"1","runtimeFormat":"LITERTLM",""" +
            """"fileFormat":"LITERTLM","mimeType":"application/x-litertlm","containerType":"qwen3"}"""

    private fun catalog(generatedAt: Long, vararg models: String, source: String = "AndroLLM fork catalog (hasanagha15)") =
        """{"schemaVersion":2,"source":"$source","generatedAt":$generatedAt,"models":[${models.joinToString(",")}]}"""

    @Test
    fun newestValidCatalogWinsAndUnpinnedMainIsIgnored() {
        val upstreamOnMain = catalog(now, model("upstream", revision = "main"))
        val integration = catalog(now - 1000, model("integration"))
        val dModels = catalog(now - 5000, model("d"))
        val body = HfCatalogRemoteSource.selectNewestValid(
            listOf(
                "main" to FetchResult(body = upstreamOnMain),
                "integration" to FetchResult(body = integration),
                "d" to FetchResult(body = dModels),
            ),
            now
        )
        assertEquals(integration, body)
    }

    @Test
    fun missingBranchesAreSkipped() {
        val dModels = catalog(now - 5000, model("d"))
        val body = HfCatalogRemoteSource.selectNewestValid(
            listOf(
                "main" to FetchResult(error = "HTTP 404"),
                "integration" to FetchResult(error = "HTTP 404"),
                "d" to FetchResult(body = dModels),
            ),
            now
        )
        assertEquals(dModels, body)
    }

    @Test
    fun emptyFutureAndGarbageCatalogsAreRejected() {
        try {
            HfCatalogRemoteSource.selectNewestValid(
                listOf(
                    "empty" to FetchResult(body = catalog(now)),
                    "future" to FetchResult(body = catalog(now + 3L * 24 * 3600 * 1000, model("f"))),
                    "html" to FetchResult(body = "<html>not json</html>"),
                ),
                now
            )
            fail("expected no usable catalog")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("no models"))
            assertTrue(e.message, e.message!!.contains("future"))
        }
    }

    @Test
    fun pinnedUpstreamCatalogOnMainWithNewerDateDoesNotWin() {
        val upstreamPinned = catalog(now, model("upstream"), source = "official LiteRT-LM catalog")
        val dModels = catalog(now - 5000, model("d"))
        val body = HfCatalogRemoteSource.selectNewestValid(
            listOf("main" to FetchResult(body = upstreamPinned), "d" to FetchResult(body = dModels)),
            now
        )
        assertEquals(dModels, body)
    }
}
