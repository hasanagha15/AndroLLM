package io.androllm.feature.models.catalog

import io.androllm.core.models.Model
import io.androllm.core.models.catalog.CatalogModel
import io.androllm.core.models.catalog.CatalogParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPresentationTest {

    @Test
    fun experimentalStatusGetsAVisibleFirstBadge() {
        val m = CatalogModel(id = "x", name = "X", status = "EXPERIMENTAL", badges = listOf("🌐 Multilingual"))
        val badges = catalogCardBadges(m, recommended = true)
        assertEquals("🧪 Experimental", badges.first())
        assertEquals(listOf("🧪 Experimental", "⭐ Recommended", "🌐 Multilingual"), badges)
    }

    @Test
    fun stableStatusHasNoStatusBadge() {
        assertNull(statusBadge("STABLE"))
        assertEquals(listOf("a"), catalogCardBadges(CatalogModel(id = "x", name = "X", badges = listOf("a")), false))
    }

    @Test
    fun bundledHybridsShowTheExperimentalBadge() {
        val models = CatalogParser.parse(File("../../core/models/src/main/assets/catalog_v1.json").readText())
            .catalog.models.associateBy { it.id }
        for (id in listOf("litertlm-qwen3.5-2b", "litertlm-qwen3.5-4b-int4", "litertlm-falcon-h1-3b-instruct")) {
            assertEquals(id, "🧪 Experimental", catalogCardBadges(models.getValue(id), false).first())
        }
        assertTrue(catalogCardBadges(models.getValue("litertlm-qwen3-4b-instruct-2507"), false)
            .none { it.contains("Experimental") })
    }

    @Test
    fun familyChangesAreMigratedToDownloadedRows() {
        val rows = listOf(
            Model(id = "ministral", name = "M", family = "Mistral", architecture = "mistral3", isDownloaded = true),
            Model(id = "same", name = "S", family = "Qwen", architecture = "qwen3"),
            Model(id = "imported", name = "I", family = "Llama", architecture = "llama"),
        )
        val catalog = listOf(
            CatalogModel(id = "ministral", name = "M", family = "Ministral", architecture = "mistral3"),
            CatalogModel(id = "same", name = "S", family = "Qwen", architecture = "qwen3"),
        )
        val updates = rows.withCatalogFamilyMigrations(catalog)
        assertEquals(1, updates.size)
        assertEquals("Ministral", updates.single().family)
        assertTrue(updates.single().isDownloaded)
    }
}
