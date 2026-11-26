package io.androllm.core.jarvislink

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvislink.TestTasks.KEY
import io.androllm.core.jarvislink.events.EventOutbox
import io.androllm.core.jarvislink.events.JarvisEventOutbox
import io.androllm.core.jarvislink.events.OutboxStorage
import io.androllm.core.jarvislink.events.PendingEvent
import io.androllm.core.jarvislink.protocol.JarvisProtocol
import io.androllm.core.jarvislink.protocol.TaskParse
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** Branch R1: phone → Jarvis events (`apps.changed`) — envelope and outbox. */
class JarvisEventTest {

    private val now = 1_800_000_000_000L
    private val body = buildJsonObject {
        put("pkg", "com.example.video"); put("oldVc", 150); put("newVc", 151)
        put("kind", "updated"); put("ts", now); put("sensitive", false)
    }

    @Test
    fun `event envelope round-trips and hides the package name from broker clients`() {
        val wire = JarvisProtocol.encodeEvent("ev-1", "apps.changed", body, now, KEY)
        val text = wire.toString(Charsets.UTF_8)
        assertThat(text).doesNotContain("com.example.video")
        val env = Json.parseToJsonElement(text).jsonObject
        assertThat(env.keys).containsExactly("v", "kind", "n", "c")
        assertThat((env["kind"] as JsonPrimitive).content).isEqualTo("event")

        val e = JarvisProtocol.decodeEvent(wire, KEY)!!
        assertThat(e.id).isEqualTo("ev-1")
        assertThat(e.type).isEqualTo("apps.changed")
        assertThat(e.body).isEqualTo(body)
        assertThat(e.ts).isEqualTo(now)
    }

    @Test
    fun `an event is never a task or a result, and a wrong key does not open it`() {
        val wire = JarvisProtocol.encodeEvent("ev-2", "apps.changed", body, now, KEY)
        assertThat(JarvisProtocol.parseTask(wire, now, KEY)).isInstanceOf(TaskParse.Invalid::class.java)
        assertThat((JarvisProtocol.parseTask(wire, now, KEY) as TaskParse.Invalid).id).isNull()
        assertThat(JarvisProtocol.decodeResult(wire, KEY)).isNull()
        assertThat(JarvisProtocol.decodeEvent(wire, ByteArray(32) { 7 })).isNull()
        // A result is not an event either.
        val result = JarvisProtocol.encodeResult("r-1", io.androllm.core.jarvislink.protocol.ResultStatus.OK, "x", now, KEY)
        assertThat(JarvisProtocol.decodeEvent(result, KEY)).isNull()
    }

    /**
     * Contract with the deployed pi-brain subscriber (R2 `apps_changed_sub.py`, decoder `event-v2`):
     * this envelope was produced there with Python `cryptography` (HKDF info `event-v2`, AAD
     * `androllm/v2/event`, test key, nonce 00..0b). The phone must produce exactly these bytes
     * and decode them.
     */
    @Test
    fun `cross-language vector - identical to the pi-brain event-v2 encoder`() {
        val vector = "{\"v\":2,\"kind\":\"event\",\"n\":\"AAECAwQFBgcICQoL\",\"c\":\"" +
            "G4sLCIYM3bsmnm1mUK9WmZNCZrAuzhgR1cqP/n9ZpACI8/26YHRhoMHmUJORiVtxMzlmZRK3AmcHDhkAPx8a3OqNe3hNmptQVtihiUhS" +
            "ScnjjVs0CRNAVbt89cu2biHyGxOKKQaEdI9wH4ymvIeBMKh5o6fBvjm7HBgv7PZrYdLyuYUG3WIhUZgxml0ZUIb4x2DhAsAiMxfin/FQ" +
            "LWrKV3UikdVyricERsGjR3FtlWcpp/4yL4Hj\"}"
        val vectorBody = buildJsonObject {
            put("pkg", "com.example.video"); put("oldVc", 150); put("newVc", 151)
            put("kind", "updated"); put("ts", 1_795_000_000_000L); put("sensitive", false)
        }
        val ours = JarvisProtocol.encodeEvent("ev-1", "apps.changed", vectorBody, 1_795_000_000_000L, KEY, nonce = ByteArray(12) { it.toByte() })
        assertThat(ours.toString(Charsets.UTF_8)).isEqualTo(vector)
        val e = JarvisProtocol.decodeEvent(vector.toByteArray(Charsets.UTF_8), KEY)!!
        assertThat(e.type).isEqualTo("apps.changed")
        assertThat(e.body).isEqualTo(vectorBody)
    }

    private class MemoryStorage : OutboxStorage {
        var events: List<PendingEvent> = emptyList()
        override fun load() = events
        override fun save(events: List<PendingEvent>) { this.events = events }
    }

    @Test
    fun `outbox keeps events until published, in order, and stops at the first failure`() = runTest {
        val storage = MemoryStorage()
        var t = now
        var n = 0
        val outbox = EventOutbox(storage, clock = { t }, newId = { "ev-${++n}" })
        outbox.add("apps.changed", body)
        outbox.add("apps.changed", body)
        outbox.add("apps.changed", body)

        val sent = mutableListOf<String>()
        var fail = 2
        val count = outbox.flush(KEY) { payload ->
            if (sent.size == fail) throw IOException("not connected")
            sent += JarvisProtocol.decodeEvent(payload, KEY)!!.id
        }
        assertThat(count).isEqualTo(2)
        assertThat(sent).containsExactly("ev-1", "ev-2").inOrder()
        assertThat(storage.events.map { it.id }).containsExactly("ev-3")

        fail = Int.MAX_VALUE
        assertThat(outbox.flush(KEY) { sent += JarvisProtocol.decodeEvent(it, KEY)!!.id }).isEqualTo(1)
        assertThat(storage.events).isEmpty()
    }

    @Test
    fun `outbox is bounded and drops expired events`() = runTest {
        val storage = MemoryStorage()
        var t = now
        var n = 0
        val outbox = EventOutbox(storage, clock = { t }, newId = { "ev-${++n}" }, maxEvents = 3, maxAgeMs = 1_000)
        repeat(5) { outbox.add("apps.changed", body) }
        assertThat(outbox.pending().map { it.id }).containsExactly("ev-3", "ev-4", "ev-5").inOrder()
        t += 2_000
        assertThat(outbox.pending()).isEmpty()
        assertThat(outbox.add("Bad Type!", body)).isNull()
    }

    @Test
    fun `nothing is queued while the link is off`() = runTest {
        val storage = MemoryStorage()
        val sink = JarvisEventOutbox(EventOutbox(storage), linkEnabled = { false }, signingKey = { KEY })
        sink.publish("apps.changed", buildJsonObject { put("pkg", "com.example.video"); put("oldVc", JsonNull) })
        assertThat(storage.events).isEmpty()
    }
}
