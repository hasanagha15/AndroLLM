package io.androllm.core.models.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLoaderTest {

    private fun catalogJson(model: String, generatedAt: Long = 0) =
        """{"schemaVersion":2,"generatedAt":$generatedAt,"models":[$model]}"""

    private fun model(
        id: String = "test-1",
        arch: String = "qwen3",
        quant: String = "Q4_K_M",
        revision: String = PIN,
    ) =
        """{"id":"$id","name":"Test Model","family":"Qwen","architecture":"$arch",""" +
            """"categories":["CHAT"],"tags":["fast"],"license":"Apache-2.0","author":"T",""" +
            """"repoId":"t/test-litertlm","revision":"$revision","fileName":"test-$quant.litertlm",""" +
            """"downloadUrl":"https://huggingface.co/t/test-litertlm/resolve/$revision/test-$quant.litertlm",""" +
            """"sizeBytes":1000000000,"parameters":"1.5B","quantization":"$quant",""" +
            """"contextLength":8192,"minRamGb":2.0,"recommendedRamGb":4.0,"downloads":100,"likes":5,""" +
            """"version":"1.0.0","runtimeFormat":"LITERTLM","fileFormat":"LITERTLM",""" +
            """"mimeType":"application/x-litertlm","containerType":"qwen3"}"""

    @Test
    fun bundledCatalogLoadsWhenNothingSaved() {
        val state = CatalogLoader.load(null, catalogJson(model()))
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertEquals(CatalogSource.BUNDLED, state.source)
        assertEquals(1, state.catalog.models.size)
    }

    @Test
    fun savedCatalogWinsOverBundled() {
        val state = CatalogLoader.load(catalogJson(model("saved-1")), catalogJson(model("bundled-1")))
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertEquals(CatalogSource.SAVED, state.source)
        assertEquals("saved-1", state.catalog.models.first().id)
    }

    @Test
    fun invalidSavedCatalogFallsBackToBundled() {
        val state = CatalogLoader.load("not json at all", catalogJson(model("bundled-1")))
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertEquals(CatalogSource.BUNDLED, state.source)
        assertEquals("bundled-1", state.catalog.models.first().id)
    }

    @Test
    fun staleSavedCatalogFallsBackToNewerBundled() {
        // A catalog persisted by an older app version (schema v1) must never
        // shadow the newer bundled catalog, or users would keep seeing the
        // old model list forever.
        val staleSaved = """{"schemaVersion":1,"models":[${model("old-1")}]}"""
        val state = CatalogLoader.load(staleSaved, catalogJson(model("bundled-1")))
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertEquals(CatalogSource.BUNDLED, state.source)
        assertEquals("bundled-1", state.catalog.models.first().id)
    }

    @Test
    fun missingBundledAssetBecomesFailed() {
        val state = CatalogLoader.load(null, "")
        assertTrue(state is CatalogState.Failed)
    }

    @Test
    fun applyAcceptsValidRemoteCatalog() {
        val state = CatalogLoader.apply(catalogJson(model("remote-1")), CatalogSource.REMOTE)
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertEquals(CatalogSource.REMOTE, state.source)
        assertEquals("remote-1", state.catalog.models.first().id)
    }

    @Test
    fun applyRejectsCatalogWithUnsupportedArchitecture() {
        val state = CatalogLoader.apply(catalogJson(model("bad", arch = "not-a-real-arch")), CatalogSource.REMOTE)
        assertTrue(state is CatalogState.Failed)
        assertTrue((state as CatalogState.Failed).message.contains("architecture"))
    }

    @Test
    fun applyRejectsGarbageJson() {
        val state = CatalogLoader.apply("{broken", CatalogSource.REMOTE)
        assertTrue(state is CatalogState.Failed)
    }

    // ---- fork: immutable-revision policy + stale saved catalog ----

    @Test
    fun applyRejectsRemoteCatalogWithUnpinnedUrls() {
        // Upstream's catalog uses mutable resolve/main links: never applied.
        val state = CatalogLoader.apply(catalogJson(model("remote-1", revision = "main")), CatalogSource.REMOTE)
        assertTrue(state is CatalogState.Failed)
        assertTrue((state as CatalogState.Failed).message.contains("not pinned"))
    }

    @Test
    fun unpinnedSavedCatalogFallsBackToBundled() {
        val saved = catalogJson(model("upstream-1", revision = "main"), generatedAt = 9_999_999_999_999)
        val state = CatalogLoader.load(saved, catalogJson(model("bundled-1"), generatedAt = 1))
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertEquals(CatalogSource.BUNDLED, state.source)
        assertEquals("bundled-1", state.catalog.models.first().id)
    }

    @Test
    fun olderSavedCatalogDoesNotShadowNewerBundled() {
        val state = CatalogLoader.load(
            catalogJson(model("saved-old"), generatedAt = 100),
            catalogJson(model("bundled-new"), generatedAt = 200)
        )
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertEquals(CatalogSource.BUNDLED, state.source)
        assertEquals("bundled-new", state.catalog.models.first().id)
    }

    @Test
    fun newerPinnedSavedCatalogWins() {
        val state = CatalogLoader.load(
            catalogJson(model("saved-new"), generatedAt = 300),
            catalogJson(model("bundled-old"), generatedAt = 200)
        )
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertEquals(CatalogSource.SAVED, state.source)
        assertEquals("saved-new", state.catalog.models.first().id)
    }

    @Test
    fun bundledCatalogMayStillCarryUnpinnedEntriesAsWarnings() {
        val state = CatalogLoader.load(null, catalogJson(model(revision = "main")))
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertTrue(state.warnings.any { it.contains("not pinned") })
    }

    // ---- fork: Gate D N6/N8 ----

    @Test
    fun emptySavedCatalogDoesNotHideTheBundledModels() {
        val emptySaved = """{"schemaVersion":2,"generatedAt":300,"models":[]}"""
        val state = CatalogLoader.load(emptySaved, catalogJson(model("bundled-1"), generatedAt = 200), nowMs = 1_000)
        state as CatalogState.Ready
        assertEquals(CatalogSource.BUNDLED, state.source)
    }

    @Test
    fun emptyRemoteCatalogIsRejected() {
        val state = CatalogLoader.apply("""{"schemaVersion":2,"generatedAt":1,"models":[]}""", CatalogSource.REMOTE, 1_000)
        assertTrue((state as CatalogState.Failed).message.contains("no models"))
    }

    @Test
    fun futureDatedCatalogIsRejectedAsSavedAndRemote() {
        val now = 1_800_000_000_000L
        val future = catalogJson(model("future"), generatedAt = now + CatalogLoader.MAX_FUTURE_SKEW_MS + 1)
        assertTrue(CatalogLoader.apply(future, CatalogSource.REMOTE, now) is CatalogState.Failed)
        val state = CatalogLoader.load(future, catalogJson(model("bundled-1"), generatedAt = 1), now)
        state as CatalogState.Ready
        assertEquals("bundled-1", state.catalog.models.first().id)
        // Within the one-day skew allowance it is still accepted.
        val nearFuture = catalogJson(model("near"), generatedAt = now + 60_000)
        assertTrue(CatalogLoader.apply(nearFuture, CatalogSource.REMOTE, now) is CatalogState.Ready)
    }

    @Test
    fun equalDatesPreferTheSavedCatalog() {
        val state = CatalogLoader.load(
            catalogJson(model("saved"), generatedAt = 500),
            catalogJson(model("bundled"), generatedAt = 500),
            nowMs = 1_000
        )
        state as CatalogState.Ready
        assertEquals(CatalogSource.SAVED, state.source)
    }

    @Test
    fun remoteUpdateNeverMovesBackwards() {
        val older = CatalogFile(schemaVersion = 2, generatedAt = 100)
        val newer = CatalogFile(schemaVersion = 2, generatedAt = 200)
        assertTrue(CatalogLoader.isAcceptableUpdate(null, older))
        assertTrue(CatalogLoader.isAcceptableUpdate(older, newer))
        assertTrue(CatalogLoader.isAcceptableUpdate(newer, newer))
        assertTrue(!CatalogLoader.isAcceptableUpdate(newer, older))
    }

    private companion object {
        const val PIN = "0123456789abcdef0123456789abcdef01234567"
    }
}
