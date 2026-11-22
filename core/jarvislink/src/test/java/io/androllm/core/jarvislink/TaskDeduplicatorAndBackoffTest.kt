package io.androllm.core.jarvislink

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvislink.mqtt.Backoff
import io.androllm.core.jarvislink.processing.InMemorySeenIdStore
import io.androllm.core.jarvislink.processing.TaskDeduplicator
import kotlin.random.Random
import org.junit.Test

class TaskDeduplicatorAndBackoffTest {

    @Test
    fun `first sighting is new, repeat is duplicate`() {
        val d = TaskDeduplicator()
        assertThat(d.markIfNew("a")).isTrue()
        assertThat(d.markIfNew("a")).isFalse()
        assertThat(d.markIfNew("b")).isTrue()
    }

    @Test
    fun `oldest ids are evicted beyond capacity`() {
        val d = TaskDeduplicator(capacity = 3)
        listOf("1", "2", "3", "4").forEach { d.markIfNew(it) }
        assertThat(d.contains("1")).isFalse()
        assertThat(d.contains("4")).isTrue()
        assertThat(d.size()).isEqualTo(3)
        assertThat(d.markIfNew("1")).isTrue() // evicted id is new again
    }

    @Test
    fun `seen ids survive a restart through the store`() {
        val store = InMemorySeenIdStore()
        TaskDeduplicator(store).markIfNew("sms-1")
        val afterRestart = TaskDeduplicator(store)
        assertThat(afterRestart.markIfNew("sms-1")).isFalse()
    }

    @Test
    fun `restored ids are trimmed to capacity keeping newest`() {
        val store = InMemorySeenIdStore().apply { save(listOf("a", "b", "c", "d")) }
        val d = TaskDeduplicator(store, capacity = 2)
        assertThat(d.contains("a")).isFalse()
        assertThat(d.contains("d")).isTrue()
    }

    @Test
    fun `forget allows a refused task to be resent`() {
        val d = TaskDeduplicator()
        d.markIfNew("x")
        d.forget("x")
        assertThat(d.markIfNew("x")).isTrue()
    }

    @Test
    fun `backoff grows exponentially to the cap and resets`() {
        val b = Backoff(initialMs = 1_000, maxMs = 16_000, jitterRatio = 0.0)
        assertThat(List(6) { b.nextDelayMs() }).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 16_000L).inOrder()
        b.reset()
        assertThat(b.nextDelayMs()).isEqualTo(1_000L)
        assertThat(b.authFailureDelayMs()).isEqualTo(16_000L)
    }

    @Test
    fun `backoff jitter stays within bounds`() {
        val b = Backoff(initialMs = 2_000, maxMs = 120_000, jitterRatio = 0.2, random = Random(42))
        repeat(50) {
            val d = b.nextDelayMs()
            assertThat(d).isAtLeast(1_000L)
            assertThat(d).isAtMost(120_000L)
        }
    }
}
