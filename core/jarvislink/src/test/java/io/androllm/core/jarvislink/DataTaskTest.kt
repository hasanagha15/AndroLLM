package io.androllm.core.jarvislink

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvislink.processing.RemoteTaskProcessor
import io.androllm.core.jarvislink.processing.RemoteTaskTracker
import io.androllm.core.jarvislink.processing.ResultPublisher
import io.androllm.core.jarvislink.processing.TaskDeduplicator
import io.androllm.core.jarvislink.processing.TaskOutcome
import io.androllm.core.jarvislink.protocol.JarvisProtocol
import io.androllm.core.jarvislink.protocol.ResultMessage
import io.androllm.core.jarvislink.protocol.ResultStatus
import io.androllm.core.jarvislink.protocol.TaskParse
import io.androllm.core.tools.remote.RemoteDataResult
import io.androllm.core.tools.remote.RemoteDataTaskHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** Branch N: typed data tasks (playbooks.sync / playbooks.relearn) on the existing encrypted task channel. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataTaskTest {

    private val now = 1_800_000_000_000L
    private val body = buildJsonObject { put("format", "androllm.playbooks") }

    @Test
    fun `typed task parses with body and without text`() {
        val payload = JarvisProtocol.encodeTask(TestTasks.KEY, "s1", "", now, type = "playbooks.sync", body = body)
        val t = (JarvisProtocol.parseTask(payload, now, TestTasks.KEY) as TaskParse.Valid).task
        assertThat(t.type).isEqualTo("playbooks.sync")
        assertThat(t.body).isEqualTo(body)
    }

    @Test
    fun `malformed type, body on a text task and oversized text tasks are invalid`() {
        val badType = JarvisProtocol.encodeTask(TestTasks.KEY, "s2", "", now, type = "Bad Type!", body = body)
        assertThat((JarvisProtocol.parseTask(badType, now, TestTasks.KEY) as TaskParse.Invalid).reason).contains("type")
        val bodyOnText = JarvisProtocol.encodeTask(TestTasks.KEY, "s3", "hello", now, body = body)
        assertThat(JarvisProtocol.parseTask(bodyOnText, now, TestTasks.KEY)).isInstanceOf(TaskParse.Invalid::class.java)
        // A large bundle is fine as a typed task, a large text task still is not.
        val big = buildJsonObject { put("pad", "x".repeat(40_000)) }
        val bigTyped = JarvisProtocol.encodeTask(TestTasks.KEY, "s4", "", now, type = "playbooks.sync", body = big)
        assertThat(JarvisProtocol.parseTask(bigTyped, now, TestTasks.KEY)).isInstanceOf(TaskParse.Valid::class.java)
        // A normal text task is fine; one whose envelope exceeds 16 KiB is refused (authentic → answered by id).
        val okText = JarvisProtocol.encodeTask(TestTasks.KEY, "s5", "y".repeat(3_000), now)
        assertThat(JarvisProtocol.parseTask(okText, now, TestTasks.KEY)).isInstanceOf(TaskParse.Valid::class.java)
        val bigText = JarvisProtocol.encodeTask(TestTasks.KEY, "s7", "y".repeat(13_000), now)
        assertThat(bigText.size).isGreaterThan(JarvisProtocol.MAX_PAYLOAD_BYTES)
        val refused = JarvisProtocol.parseTask(bigText, now, TestTasks.KEY) as TaskParse.Invalid
        assertThat(refused.reason).isEqualTo("payload too large")
        assertThat(refused.id).isEqualTo("s7")
        // Forged (wrong key) typed task: never answered.
        val forged = JarvisProtocol.encodeTask(TestTasks.OTHER_KEY, "s6", "", now, type = "playbooks.sync", body = body)
        assertThat((JarvisProtocol.parseTask(forged, now, TestTasks.KEY) as TaskParse.Invalid).id).isNull()
    }

    @Test
    fun `typed tasks go to their handler, never to the agent runner`() = runTest {
        val published = mutableListOf<ResultMessage>()
        var agentRuns = 0
        val seen = mutableListOf<Pair<String, JsonObject?>>()
        val handler = object : RemoteDataTaskHandler {
            override val types = setOf("playbooks.sync")
            override suspend fun handle(type: String, body: JsonObject?): RemoteDataResult {
                seen += type to body
                return RemoteDataResult(true, "imported 1, rejected 0")
            }
        }
        val p = RemoteTaskProcessor(
            runner = { agentRuns++; TaskOutcome(ResultStatus.OK, "agent") },
            publisher = ResultPublisher { published += JarvisProtocol.decodeResult(it, TestTasks.KEY)!! },
            deduplicator = TaskDeduplicator(),
            tracker = RemoteTaskTracker(),
            signingKey = { TestTasks.KEY },
            clock = { now },
            dataHandlers = setOf(handler)
        ).also { it.start(backgroundScope) }

        p.onPayload(JarvisProtocol.encodeTask(TestTasks.KEY, "d1", "", now, type = "playbooks.sync", body = body))
        p.onPayload(JarvisProtocol.encodeTask(TestTasks.KEY, "d2", "", now, type = "playbooks.unknown"))
        runCurrent()
        assertThat(agentRuns).isEqualTo(0)
        assertThat(seen.single()).isEqualTo("playbooks.sync" to body)
        assertThat(published.filter { it.id == "d1" }.map { it.status }).containsExactly(ResultStatus.ACCEPTED, ResultStatus.OK).inOrder()
        assertThat(published.last { it.id == "d1" }.text).isEqualTo("imported 1, rejected 0")
        assertThat(published.last { it.id == "d2" }.status).isEqualTo(ResultStatus.INVALID)
    }

    @Test
    fun `branch W - a handler may name the final status, never ok or accepted for a failure`() = runTest {
        val published = mutableListOf<ResultMessage>()
        val handler = object : RemoteDataTaskHandler {
            override val types = setOf("playbook.run")
            override suspend fun handle(type: String, body: JsonObject?): RemoteDataResult {
                val st = (body?.get("st") as? kotlinx.serialization.json.JsonPrimitive)?.content
                return RemoteDataResult(false, "{\"outcome\":\"x\"}", status = st)
            }
        }
        val p = RemoteTaskProcessor(
            runner = { TaskOutcome(ResultStatus.OK, "agent") },
            publisher = ResultPublisher { published += JarvisProtocol.decodeResult(it, TestTasks.KEY)!! },
            deduplicator = TaskDeduplicator(),
            tracker = RemoteTaskTracker(),
            signingKey = { TestTasks.KEY },
            clock = { now },
            queueCapacity = 8,
            dataHandlers = setOf(handler)
        ).also { it.start(backgroundScope) }
        val cases = listOf("w1" to "denied", "w2" to "error", "w3" to "ok", "w4" to "accepted", "w5" to "bogus")
        for ((id, st) in cases) {
            val b = kotlinx.serialization.json.buildJsonObject { put("st", kotlinx.serialization.json.JsonPrimitive(st)) }
            p.onPayload(JarvisProtocol.encodeTask(TestTasks.KEY, id, "", now, ttl = 60, type = "playbook.run", body = b))
        }
        runCurrent()
        fun final(id: String) = published.last { it.id == id }.status
        assertThat(final("w1")).isEqualTo(ResultStatus.DENIED)
        assertThat(final("w2")).isEqualTo(ResultStatus.ERROR)
        assertThat(final("w3")).isEqualTo(ResultStatus.INVALID)
        assertThat(final("w4")).isEqualTo(ResultStatus.INVALID)
        assertThat(final("w5")).isEqualTo(ResultStatus.INVALID)
    }

    @Test
    fun `branch W - playbook run tasks may not live longer than 300 s`() {
        val long = JarvisProtocol.encodeTask(TestTasks.KEY, "t1", "", now, ttl = 600, type = "playbook.run", body = body)
        assertThat(JarvisProtocol.parseTask(long, now, TestTasks.KEY)).isInstanceOf(TaskParse.Invalid::class.java)
        val ok = JarvisProtocol.encodeTask(TestTasks.KEY, "t2", "", now, ttl = 300, type = "playbook.run", body = body)
        assertThat(JarvisProtocol.parseTask(ok, now, TestTasks.KEY)).isInstanceOf(TaskParse.Valid::class.java)
        val sync = JarvisProtocol.encodeTask(TestTasks.KEY, "t3", "", now, ttl = 600, type = "playbooks.sync", body = body)
        assertThat(JarvisProtocol.parseTask(sync, now, TestTasks.KEY)).isInstanceOf(TaskParse.Valid::class.java)
    }
}
