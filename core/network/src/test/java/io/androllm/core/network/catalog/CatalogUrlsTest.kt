package io.androllm.core.network.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork policy: the catalog is refreshed only from the owner's fork, never from upstream. */
class CatalogUrlsTest {

    @Test
    fun refreshUsesOnlyTheForkCatalog() {
        val urls = HfCatalogRemoteSource.CATALOG_URLS
        assertEquals(1, urls.size)
        urls.forEach { url ->
            assertTrue(url, url.startsWith("https://raw.githubusercontent.com/hasanagha15/AndroLLM/refs/heads/"))
            assertTrue(url, url.endsWith("/core/models/src/main/assets/catalog_v1.json"))
        }
    }

    @Test
    fun upstreamHostsAreGone() {
        val joined = HfCatalogRemoteSource.CATALOG_URLS.joinToString(" ")
        assertTrue(!joined.contains("models.androllm.com"))
        assertTrue(!joined.contains("ShadowSafin"))
    }
}
