package io.androllm.feature.models.catalog

import io.androllm.core.models.Model
import io.androllm.core.models.catalog.CatalogModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogArtifactRefreshTest {

    private val oldUrl = "https://huggingface.co/a/b/resolve/main/m.litertlm"
    private val newUrl = "https://huggingface.co/a/b/resolve/0123456789abcdef0123456789abcdef01234567/m.litertlm"

    private val row = Model(
        id = "m", name = "M", downloadUrl = oldUrl, sha256 = "a".repeat(64), fileSize = 10,
        filePath = "/data/m.litertlm",
    )

    private fun entry(url: String = newUrl, sha: String? = "b".repeat(64), size: Long = 20) =
        CatalogModel(id = "m", name = "M", downloadUrl = url, sha256 = sha, sizeBytes = size)

    @Test
    fun retryTakesUrlShaAndSizeFromTheCurrentCatalog() {
        val r = row.withCurrentCatalogArtifact(listOf(entry()))
        assertTrue(r.artifactChanged)
        assertEquals(newUrl, r.model.downloadUrl)
        assertEquals("b".repeat(64), r.model.sha256)
        assertEquals(20, r.model.fileSize)
        assertEquals("/data/m.litertlm", r.model.filePath)
    }

    @Test
    fun unchangedArtifactKeepsTheRowAsIs() {
        val same = entry(url = oldUrl, sha = "A".repeat(64), size = 10)
        val r = row.withCurrentCatalogArtifact(listOf(same))
        assertFalse(r.artifactChanged)
        assertSame(row, r.model)
    }

    @Test
    fun modelsMissingFromTheCatalogAreLeftAlone() {
        val r = row.withCurrentCatalogArtifact(listOf(entry().copy(id = "other")))
        assertFalse(r.artifactChanged)
        assertSame(row, r.model)
    }

    @Test
    fun shaChangeAloneCountsAsANewArtifact() {
        val r = row.withCurrentCatalogArtifact(listOf(entry(url = oldUrl)))
        assertTrue(r.artifactChanged)
    }
}
