package io.androllm.feature.models.catalog

import io.androllm.core.models.Model
import io.androllm.core.models.catalog.CatalogModel

/**
 * AndroLLM fork (Gate D re-gate, N1/N3): catalog presentation and metadata
 * migration helpers, kept pure for unit tests.
 */

/** Badge shown on catalog cards for a non-stable status; null for STABLE. */
fun statusBadge(status: String): String? = when (status.uppercase()) {
    "EXPERIMENTAL" -> "🧪 Experimental"
    "BETA" -> "🧪 Beta"
    "ARCHIVED" -> "📦 Archived"
    else -> null
}

/** All badges for a catalog card: status first (always visible), then recommended, then the catalog's own. */
fun catalogCardBadges(model: CatalogModel, recommended: Boolean): List<String> = buildList {
    statusBadge(model.status)?.let { add(it) }
    if (recommended) add("⭐ Recommended")
    model.badges.filterNot { it in this }.forEach { add(it) }
}

/**
 * Family / architecture are copied into the database row when a model is
 * downloaded, and the engine resolves the chat template from that row. When
 * the catalog later changes them (e.g. Ministral: Mistral -> Ministral so the
 * container template is used), already-downloaded models would keep the old
 * value. Returns the rows that need updating; untouched rows are not returned.
 */
fun List<Model>.withCatalogFamilyMigrations(catalog: List<CatalogModel>): List<Model> {
    val byId = catalog.associateBy { it.id }
    return mapNotNull { row ->
        val entry = byId[row.id] ?: return@mapNotNull null
        val family = entry.family.takeIf { it.isNotBlank() } ?: row.family
        val architecture = entry.architecture.takeIf { it.isNotBlank() } ?: row.architecture
        if (family == row.family && architecture == row.architecture) null
        else row.copy(family = family, architecture = architecture)
    }
}
