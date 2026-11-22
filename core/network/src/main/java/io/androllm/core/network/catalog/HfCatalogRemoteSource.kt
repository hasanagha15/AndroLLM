package io.androllm.core.network.catalog

import io.androllm.core.common.Result
import io.androllm.core.models.catalog.CatalogRemoteSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the latest model catalog JSON from the AndroLLM fork's GitHub copy
 * (see [CATALOG_URLS]; upstream hosts were removed on purpose).
 *
 * Future-proofing: the catalog is plain metadata, so new official LiteRT-LM
 * models can be added server-side without an app update. When every endpoint
 * is unreachable (offline, or the file has not been published yet),
 * [CatalogRepository] simply keeps the bundled catalog asset.
 */
@Singleton
class HfCatalogRemoteSource @Inject constructor(
    private val client: HttpClient
) : CatalogRemoteSource {

    override suspend fun fetchCatalogJson(): Result<String> = io.androllm.core.common.runCatching {
        var lastError: Throwable? = null
        for (url in CATALOG_URLS) {
            try {
                val response = client.get(url)
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("Catalog fetch failed: HTTP ${response.status.value}")
                }
                val body = response.body<String>()
                if (body.isNotBlank()) return@runCatching body
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("No catalog endpoint available")
    }

    companion object {
        /**
         * Endpoints tried in order. AndroLLM fork: the catalog is refreshed ONLY
         * from the owner's fork, never from upstream. Upstream's catalog uses
         * mutable `resolve/main` URLs whose files were re-exported for newer
         * LiteRT-LM releases (hash mismatches / 404s on this app's 0.16.0
         * runtime), and a fetched catalog overrides the bundled one. The fork's
         * catalog pins every file to an immutable Hugging Face commit, and
         * CatalogLoader additionally rejects any catalog with unpinned URLs,
         * so even a wrong endpoint cannot replace the bundled catalog.
         *
         * `refs/heads/...` makes the branch ref unambiguous for a name with a
         * slash. When the catalog branch is merged elsewhere, update [FORK_BRANCH].
         */
        const val FORK_REPO = "hasanagha15/AndroLLM"
        const val FORK_BRANCH = "jarvis/d-models"
        const val CATALOG_PATH = "core/models/src/main/assets/catalog_v1.json"

        val CATALOG_URLS: List<String> = listOf(
            "https://raw.githubusercontent.com/$FORK_REPO/refs/heads/$FORK_BRANCH/$CATALOG_PATH"
        )
    }
}
