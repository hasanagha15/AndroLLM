package io.androllm.feature.models.catalog

import io.androllm.core.models.Model
import io.androllm.core.models.catalog.CatalogModel

/**
 * AndroLLM fork (Gate D, N5): the Downloads tab's Retry/Resume used the URL
 * and sha256 stored in the model's database row, so after a catalog refresh it
 * kept re-trying the old (possibly replaced or removed) file. This re-reads the
 * artifact identity from the CURRENT catalog entry with the same id.
 */
data class ArtifactRefresh(
    val model: Model,
    /** True when URL or sha256 changed: any partial bytes belong to another file. */
    val artifactChanged: Boolean,
)

fun Model.withCurrentCatalogArtifact(catalog: List<CatalogModel>): ArtifactRefresh {
    val entry = catalog.firstOrNull { it.id == id } ?: return ArtifactRefresh(this, false)
    val newSha = entry.sha256?.takeIf { it.isNotBlank() }
    val changed = entry.downloadUrl != downloadUrl ||
        !newSha.equals(sha256?.takeIf { it.isNotBlank() }, ignoreCase = true)
    if (!changed) return ArtifactRefresh(this, false)
    return ArtifactRefresh(
        copy(
            downloadUrl = entry.downloadUrl,
            sha256 = newSha,
            fileSize = entry.sizeBytes,
            companionUrl = entry.companionUrl.takeIf { it.isNotBlank() },
        ),
        artifactChanged = true,
    )
}
