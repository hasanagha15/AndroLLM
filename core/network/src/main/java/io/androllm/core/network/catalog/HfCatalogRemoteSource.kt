package io.androllm.core.network.catalog

import io.androllm.core.common.Result
import io.androllm.core.models.catalog.CatalogLoader
import io.androllm.core.models.catalog.CatalogRemoteSource
import io.androllm.core.models.catalog.CatalogSource
import io.androllm.core.models.catalog.CatalogState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the model catalog from the AndroLLM fork's GitHub branches (see
 * [CATALOG_URLS]; upstream hosts were removed on purpose) and returns the
 * NEWEST copy that passes [CatalogLoader.apply] — pinned URLs, validation,
 * non-empty, not dated in the future. When nothing usable is found,
 * [CatalogRepository] keeps the current (bundled/saved) catalog.
 */
@Singleton
class HfCatalogRemoteSource @Inject constructor(
    private val client: HttpClient
) : CatalogRemoteSource {

    override suspend fun fetchCatalogJson(): Result<String> = io.androllm.core.common.runCatching {
        val fetched = CATALOG_URLS.map { url ->
            url to try {
                val response = client.get(url)
                if (response.status.isSuccess()) {
                    FetchResult(body = response.body<String>())
                } else {
                    FetchResult(error = "HTTP ${response.status.value}")
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                FetchResult(error = t.message ?: t.javaClass.simpleName)
            }
        }
        selectNewestValid(fetched)
    }

    data class FetchResult(val body: String? = null, val error: String? = null)

    companion object {
        /**
         * AndroLLM fork: the catalog is refreshed ONLY from the owner's fork,
         * never from upstream. A catalog is accepted only with the fork's
         * provenance marker in `source` ([CatalogLoader.FORK_CATALOG_MARKER])
         * and pinned URLs, so upstream's catalog on the fork's `main` (or on
         * `jarvis/integration` before branch D is merged) is always rejected.
         *
         * Several branches are tried so the address does not depend on where
         * the catalog branch ends up after merging: `main` (today it still
         * holds upstream's unpinned catalog, which is rejected), the
         * integration branch and the catalog's own branch. The newest valid
         * catalog wins. `refs/heads/...` keeps branch names with a slash
         * unambiguous.
         */
        const val FORK_REPO = "hasanagha15/AndroLLM"
        val CATALOG_BRANCHES: List<String> = listOf("main", "jarvis/integration", "jarvis/d-models")
        const val CATALOG_PATH = "core/models/src/main/assets/catalog_v1.json"

        val CATALOG_URLS: List<String> = CATALOG_BRANCHES.map {
            "https://raw.githubusercontent.com/$FORK_REPO/refs/heads/$it/$CATALOG_PATH"
        }

        /**
         * Pure selection step (unit-tested): the body of the newest catalog
         * that [CatalogLoader.apply] accepts as REMOTE; on a date tie the
         * earlier URL in the list wins. Throws with every URL's reason when
         * none is usable.
         */
        fun selectNewestValid(
            fetched: List<Pair<String, FetchResult>>,
            nowMs: Long = System.currentTimeMillis()
        ): String {
            var best: Pair<Long, String>? = null
            val reasons = mutableListOf<String>()
            for ((url, result) in fetched) {
                val body = result.body
                if (body.isNullOrBlank()) {
                    reasons += "$url: ${result.error ?: "empty response"}"
                    continue
                }
                when (val state = CatalogLoader.apply(body, CatalogSource.REMOTE, nowMs)) {
                    is CatalogState.Ready -> {
                        val current = best
                        if (current == null || state.catalog.generatedAt > current.first) {
                            best = state.catalog.generatedAt to body
                        }
                    }
                    is CatalogState.Failed -> reasons += "$url: ${state.message}"
                    CatalogState.Loading -> Unit
                }
            }
            return best?.second
                ?: throw IllegalStateException("No usable fork catalog: ${reasons.joinToString("; ")}")
        }
    }
}
