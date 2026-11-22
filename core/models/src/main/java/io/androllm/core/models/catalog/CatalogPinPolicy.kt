package io.androllm.core.models.catalog

/**
 * Immutable-revision policy for catalog download URLs (AndroLLM fork).
 *
 * Hugging Face `resolve/main/...` URLs are mutable: upstream repos re-export
 * files in place (e.g. rebuilt for a newer LiteRT-LM), so the bytes stop
 * matching the catalog's sha256 and a model written for LiteRT-LM 0.16.0 can
 * silently turn into one this runtime cannot load. A pinned entry points at
 * `https://huggingface.co/<org>/<repo>/resolve/<40-hex commit>/<file>` and its
 * `revision` field names the same commit, so size + sha256 can never drift.
 *
 * The bundled catalog must be fully pinned (enforced by CatalogAssetTest);
 * a saved or remote catalog with any unpinned entry is rejected by
 * [CatalogLoader], which keeps the previous / bundled catalog instead.
 */
object CatalogPinPolicy {

    private val HF_PINNED = Regex(
        "^https://huggingface\\.co/([^/]+/[^/]+)/resolve/([0-9a-f]{40})/[^?#]+$"
    )

    /** Null when [model] is pinned, otherwise a human-readable reason. */
    fun problem(model: CatalogModel): String? {
        val main = urlProblem(model.downloadUrl, model.repoId, model.revision, "downloadUrl")
        if (main != null) return "${model.id}: $main"
        if (model.companionUrl.isNotBlank()) {
            val companion = urlProblem(model.companionUrl, null, null, "companionUrl")
            if (companion != null) return "${model.id}: $companion"
        }
        return null
    }

    fun isPinned(model: CatalogModel): Boolean = problem(model) == null

    /** Reasons for every unpinned entry; empty when the whole list is pinned. */
    fun problems(models: List<CatalogModel>): List<String> = models.mapNotNull { problem(it) }

    private fun urlProblem(url: String, repoId: String?, revision: String?, field: String): String? {
        val match = HF_PINNED.matchEntire(url)
            ?: return "$field is not pinned to an immutable Hugging Face revision " +
                "(expected https://huggingface.co/<repo>/resolve/<40-hex commit>/<file>)"
        val (urlRepo, urlRevision) = match.destructured
        if (!repoId.isNullOrBlank() && !urlRepo.equals(repoId, ignoreCase = true)) {
            return "$field repo '$urlRepo' does not match repoId '$repoId'"
        }
        if (revision != null && revision != urlRevision) {
            return "revision '$revision' does not match the commit pinned in $field ($urlRevision)"
        }
        return null
    }
}
