package io.androllm.core.jarvislink.events

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.jarvislink.protocol.JarvisProtocol
import io.androllm.core.jarvislink.settings.JarvisLinkSettingsStore
import io.androllm.core.tools.remote.JarvisEventSink
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

/** Branch R1: one event waiting for the link. Package names / numbers only. */
@Serializable
data class PendingEvent(val id: String, val type: String, val body: JsonObject, val ts: Long)

/** Where the outbox keeps its events between process restarts. */
interface OutboxStorage {
    fun load(): List<PendingEvent>
    fun save(events: List<PendingEvent>)
    /** Fingerprint of the link (broker + key) the stored events were queued for; null = none. */
    fun loadIdentity(): String?
    fun saveIdentity(identity: String?)
}

/**
 * Branch R1: bounded, persisted queue of phone → Jarvis events (pure, unit-tested).
 * Oldest first; at most [maxEvents] (the oldest are dropped) and none older
 * than [maxAgeMs]. [flush] encrypts each event with the event key and stops
 * at the first failed publish (kept for the next connect).
 */
class EventOutbox(
    private val storage: OutboxStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = ::randomEventId,
    private val maxEvents: Int = MAX_EVENTS,
    private val maxAgeMs: Long = MAX_AGE_MS
) {
    private val mutex = Mutex()

    /** Gate R1 B2: drops every pending event (link turned off, other broker or key). */
    suspend fun clear() = mutex.withLock { clearLocked() }

    private fun clearLocked() {
        storage.save(emptyList())
        storage.saveIdentity(null)
    }

    /**
     * Events queued for another link identity (broker host/port/user or key changed)
     * are dropped, never sent to the new one.
     */
    private fun bindLocked(identity: String) {
        val stored = storage.loadIdentity()
        if (stored != identity) {
            if (stored != null || storage.load().isNotEmpty()) storage.save(emptyList())
            storage.saveIdentity(identity)
        }
    }

    suspend fun add(type: String, body: JsonObject, identity: String = ""): PendingEvent? = mutex.withLock {
        if (!JarvisProtocol.isValidEventType(type)) return@withLock null
        bindLocked(identity)
        val e = PendingEvent(newId(), type, body, clock())
        val next = (live(storage.load()) + e).takeLast(maxEvents)
        storage.save(next)
        e
    }

    /** Sends pending events in order; returns how many were delivered. */
    suspend fun flush(master: ByteArray, identity: String = "", publish: suspend (ByteArray) -> Unit): Int = mutex.withLock {
        bindLocked(identity)
        val pending = live(storage.load())
        var sent = 0
        for (e in pending) {
            val payload = runCatching { JarvisProtocol.encodeEvent(e.id, e.type, e.body, e.ts, master) }.getOrNull()
            if (payload != null) {
                val ok = try {
                    publish(payload)
                    true
                } catch (c: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    false
                } catch (x: Exception) {
                    false
                }
                if (!ok) break
            } // an event that cannot be encoded (too large / malformed) is dropped
            sent++
        }
        storage.save(pending.drop(sent))
        sent
    }

    suspend fun pending(): List<PendingEvent> = mutex.withLock { live(storage.load()) }

    private fun live(events: List<PendingEvent>): List<PendingEvent> {
        val cutoff = clock() - maxAgeMs
        return events.filter { it.ts >= cutoff }
    }

    companion object {
        const val MAX_EVENTS = 200
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
        private val random = SecureRandom()

        fun randomEventId(): String {
            val b = ByteArray(6).also(random::nextBytes)
            return "ev-" + System.currentTimeMillis() + "-" + b.joinToString("") { "%02x".format(it) }
        }
    }
}

/** SharedPreferences `jarvis_event_outbox` (excluded from backup). */
class PrefsOutboxStorage(context: Context) : OutboxStorage {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PendingEvent.serializer())

    override fun load(): List<PendingEvent> = runCatching {
        json.decodeFromString(serializer, prefs.getString(KEY, null) ?: return emptyList())
    }.getOrDefault(emptyList())

    override fun save(events: List<PendingEvent>) {
        runCatching { prefs.edit().putString(KEY, json.encodeToString(serializer, events)).apply() }
    }

    override fun loadIdentity(): String? = runCatching { prefs.getString(KEY_ID, null) }.getOrNull()

    override fun saveIdentity(identity: String?) {
        runCatching { prefs.edit().putString(KEY_ID, identity).apply() }
    }

    companion object {
        const val PREFS_NAME = "jarvis_event_outbox"
        private const val KEY = "events"
        private const val KEY_ID = "link_identity"
    }
}

/**
 * Branch R1: the [JarvisEventSink] of the app. Events are queued only while
 * the Jarvis link is enabled in Settings and sent by the link service when
 * connected ([attach] / [flushNow]).
 *
 * Gate R1 B2: when the link is off the pending queue is DELETED (on publish,
 * on flush, and from the service when it stops because the link is disabled),
 * and events queued for another broker host / port / user or another key are
 * dropped instead of being sent to the new one ([identity]).
 */
@Singleton
class JarvisEventOutbox internal constructor(
    private val outbox: EventOutbox,
    private val linkEnabled: () -> Boolean,
    private val signingKey: () -> ByteArray?,
    /** Broker host/port/user of the link (no secrets). */
    private val linkTarget: () -> String = { "" }
) : JarvisEventSink {

    @Inject constructor(@ApplicationContext context: Context, settings: JarvisLinkSettingsStore) : this(
        EventOutbox(PrefsOutboxStorage(context)),
        { settings.current().enabled },
        { settings.signingKey() },
        { settings.current().let { "${it.host.lowercase()}:${it.port}:${it.username}" } }
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var publisher: (suspend (ByteArray) -> Unit)? = null

    override fun publish(type: String, body: JsonObject) {
        scope.launch {
            if (!linkEnabled()) {
                outbox.clear()
                return@launch
            }
            outbox.add(type, body, identity()) ?: return@launch
            flushNow()
        }
    }

    /** The link service: events go out through [publish] (throws when not connected). */
    fun attach(publish: suspend (ByteArray) -> Unit) {
        publisher = publish
    }

    fun detach() {
        publisher = null
    }

    /** The link was turned off (service stopping for that reason): forget pending events. */
    suspend fun discardAll() = outbox.clear()

    /** Fire-and-forget [discardAll] for non-suspending callers. */
    fun discardAllAsync() {
        scope.launch { outbox.clear() }
    }

    /** Sends what is pending (after connect, after a new event). Never throws. */
    suspend fun flushNow() {
        if (!linkEnabled()) {
            runCatching { outbox.clear() }
            return
        }
        val p = publisher ?: return
        val key = signingKey() ?: return
        val sent = runCatching { outbox.flush(key, identity(), p) }.getOrDefault(0)
        if (sent > 0) Timber.i("JarvisLink: %d event(s) sent", sent)
    }

    /**
     * Fingerprint of broker target + key: SHA-256 over a domain tag, the target and
     * the key, truncated — the key itself is never stored.
     */
    internal fun identity(): String = identityOf(linkTarget(), signingKey())

    companion object {
        internal fun identityOf(target: String, key: ByteArray?): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.update("androllm-event-outbox-v1|".toByteArray(Charsets.UTF_8))
            md.update(target.toByteArray(Charsets.UTF_8))
            md.update("|".toByteArray(Charsets.UTF_8))
            if (key != null) md.update(key)
            return md.digest().take(12).joinToString("") { "%02x".format(it) }
        }
    }
}
