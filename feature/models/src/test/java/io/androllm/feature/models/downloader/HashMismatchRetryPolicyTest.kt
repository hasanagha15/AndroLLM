package io.androllm.feature.models.downloader

import io.androllm.feature.models.downloader.HashMismatchRetryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashMismatchRetryPolicyTest {

    private class MapStore : HashMismatchRetryPolicy.CounterStore {
        val map = mutableMapOf<String, Int>()
        override fun get(key: String) = map[key] ?: 0
        override fun set(key: String, value: Int) { map[key] = value }
        override fun clear(key: String) { map.remove(key) }
    }

    private val sha = "a".repeat(64)

    @Test
    fun capIsTwoCompleteDownloads() {
        assertEquals(2, HashMismatchRetryPolicy.MAX_DOWNLOADS_WITH_MISMATCH)
    }

    @Test
    fun firstMismatchRetriesSecondGivesUp() {
        val store = MapStore()
        val key = HashMismatchRetryPolicy.key("qwen", sha)
        assertEquals(Decision.Retry(1), HashMismatchRetryPolicy.onMismatch(store, key))
        assertEquals(Decision.GiveUp(2), HashMismatchRetryPolicy.onMismatch(store, key))
    }

    @Test
    fun mismatchLoopIsBoundedNoMatterHowOftenWorkManagerRetries() {
        val store = MapStore()
        val key = HashMismatchRetryPolicy.key("qwen", sha)
        var downloads = 0
        while (downloads < 100) {
            downloads++
            if (HashMismatchRetryPolicy.onMismatch(store, key) is Decision.GiveUp) break
        }
        assertEquals(HashMismatchRetryPolicy.MAX_DOWNLOADS_WITH_MISMATCH, downloads)
    }

    @Test
    fun giveUpClearsCounterSoAManualRetryStartsFresh() {
        val store = MapStore()
        val key = HashMismatchRetryPolicy.key("qwen", sha)
        HashMismatchRetryPolicy.onMismatch(store, key)
        HashMismatchRetryPolicy.onMismatch(store, key)
        assertTrue(store.map.isEmpty())
        assertEquals(Decision.Retry(1), HashMismatchRetryPolicy.onMismatch(store, key))
    }

    @Test
    fun verifiedDownloadResetsCounter() {
        val store = MapStore()
        val key = HashMismatchRetryPolicy.key("qwen", sha)
        HashMismatchRetryPolicy.onMismatch(store, key)
        HashMismatchRetryPolicy.onVerified(store, key)
        assertEquals(Decision.Retry(1), HashMismatchRetryPolicy.onMismatch(store, key))
    }

    @Test
    fun countersAreIndependentPerModelAndPerExpectedSha() {
        val store = MapStore()
        HashMismatchRetryPolicy.onMismatch(store, HashMismatchRetryPolicy.key("a", sha))
        // A catalog update with a new sha256 for the same model starts fresh.
        assertEquals(
            Decision.Retry(1),
            HashMismatchRetryPolicy.onMismatch(store, HashMismatchRetryPolicy.key("a", "b".repeat(64)))
        )
        assertEquals(Decision.Retry(1), HashMismatchRetryPolicy.onMismatch(store, HashMismatchRetryPolicy.key("b", sha)))
    }

    @Test
    fun keyIsCaseInsensitiveOnShaAndHandlesMissingSha() {
        assertEquals(
            HashMismatchRetryPolicy.key("m", sha.uppercase()),
            HashMismatchRetryPolicy.key("m", sha)
        )
        assertEquals("m|no-sha", HashMismatchRetryPolicy.key("m", null))
    }

    @Test
    fun giveUpMessageExplainsAndStopsRetries() {
        val msg = HashMismatchRetryPolicy.giveUpMessage("Qwen3 4B", 2, sha, "b".repeat(64))
        assertTrue(msg.contains("2 complete downloads"))
        assertTrue(msg.contains("aaaaaaaaaaaaaaaa"))
        assertTrue(msg.contains("bbbbbbbbbbbbbbbb"))
        assertTrue(msg.contains("automatic retries are stopped"))
    }

    // ---- Gate D, N5: pre-download sha check via preflight X-Linked-ETag ----

    @Test
    fun serverShaDifferentFromCatalogStopsBeforeDownloading() {
        val msg = HashMismatchRetryPolicy.serverMismatchMessage("Qwen3 4B", sha, "b".repeat(64))
        assertTrue(msg!!.contains("nothing was downloaded"))
        assertTrue(msg.contains("bbbbbbbbbbbbbbbb"))
    }

    @Test
    fun matchingOrUnknownShaLetsTheDownloadStart() {
        assertEquals(null, HashMismatchRetryPolicy.serverMismatchMessage("m", sha, sha.uppercase()))
        assertEquals(null, HashMismatchRetryPolicy.serverMismatchMessage("m", sha, "\"$sha\""))
        assertEquals(null, HashMismatchRetryPolicy.serverMismatchMessage("m", sha, null))
        assertEquals(null, HashMismatchRetryPolicy.serverMismatchMessage("m", null, sha))
        assertEquals(null, HashMismatchRetryPolicy.serverMismatchMessage("m", sha, "W/\"abc\""))
    }
}
