package io.androllm.core.jarvislink.processing

/** Persists the recently seen task ids so a restart cannot replay a QoS 1 redelivery. */
interface SeenIdStore {
    fun load(): List<String>
    fun save(ids: List<String>)
}

/** In-memory store (tests, or when persistence is unavailable). */
class InMemorySeenIdStore : SeenIdStore {
    @Volatile private var ids: List<String> = emptyList()
    override fun load(): List<String> = ids
    override fun save(ids: List<String>) { this.ids = ids }
}

/**
 * Remembers the last [capacity] task ids (LRU, oldest evicted first).
 *
 * MQTT QoS 1 is at-least-once: the broker redelivers a task when our PUBACK
 * was lost, and a persistent session replays queued tasks after reconnect.
 * Every task therefore goes through [markIfNew] first — an id seen before is
 * answered `duplicate` and NEVER executed again (a re-sent "send SMS" must not
 * send twice).
 */
class TaskDeduplicator(
    private val store: SeenIdStore = InMemorySeenIdStore(),
    private val capacity: Int = DEFAULT_CAPACITY
) {
    private val lock = Any()
    private val seen = LinkedHashSet<String>()

    init {
        require(capacity > 0)
        synchronized(lock) {
            store.load().takeLast(capacity).forEach { seen.add(it) }
        }
    }

    /** Returns true (and records [id]) when it was never seen; false for a duplicate. */
    fun markIfNew(id: String): Boolean = synchronized(lock) {
        if (id in seen) return false
        seen.add(id)
        while (seen.size > capacity) {
            val oldest = seen.iterator().next()
            seen.remove(oldest)
        }
        store.save(seen.toList())
        true
    }

    /** Forgets [id] — used when a task was refused before running (busy), so Jarvis may retry it. */
    fun forget(id: String) = synchronized(lock) {
        if (seen.remove(id)) store.save(seen.toList())
    }

    fun contains(id: String): Boolean = synchronized(lock) { id in seen }

    fun size(): Int = synchronized(lock) { seen.size }

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}
