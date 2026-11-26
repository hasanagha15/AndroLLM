package io.androllm.core.appatlas.playbook

import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.appatlas.playbook.PlaybookFixtures.PKG
import io.androllm.core.appatlas.playbook.PlaybookFixtures.node
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.origin.currentRemoteOrigin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test

/** Branch W: typed Jarvis tasks playbooks.list / playbook.run (synthetic com.example.* data only). */
class JarvisPlaybookTaskHandlerTest {

    private val store = MemoryPlaybookStore()
    private var now = 1_000_000L
    private val runs = mutableListOf<Pair<String, Boolean>>() // id to "ran inside a remote origin"
    private var result: ToolResult = ToolResult.Success("Done", buildJsonObject { put("status", "ok"); put("verified", true); put("steps", 3) })
    private var runTakesMs = 10L
    private var listOn = true
    private var runOn = true
    private var busy: String? = null
    private val installed = mutableMapOf(PKG to 150L, "com.whatsapp" to 1L)

    // A fresh repository per call: PlaybookRepository caches store.all() and the tests edit the store directly.
    private val handler get() = JarvisPlaybookTaskHandler(
        repository = PlaybookRepository(store) { emptySet() },
        runPlaybook = { id, _ -> runs += id to (currentRemoteOrigin() != null); now += runTakesMs; result },
        installedVersion = { installed[it] },
        appLabel = { if (it == PKG) "Example Video" else null },
        atlasSensitive = { false },
        listEnabled = { listOn },
        runEnabled = { runOn },
        ownPackage = "io.androllm.app",
        owner = OwnerActivity { busy },
        clock = { now },
        confirmTimeoutMs = 300_000L
    )

    private fun put(p: Playbook, source: String = PlaybookRepository.SOURCE_JARVIS, verified: Int = 1, status: PlaybookStatus = PlaybookStatus.OK) {
        store.items[p.id] = StoredPlaybook(p, status = status, source = source, verifiedCount = verified, flaggedVersion = if (status == PlaybookStatus.STALE) 150L else null)
    }

    private fun runBody(id: String, deadline: Long? = now + 300_000L) = buildJsonObject {
        put("id", id); putJsonObject("args") { put("query", "cats") }
        if (deadline != null) put("deadline_ms", deadline)
    }
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun reason(r: io.androllm.core.tools.remote.RemoteDataResult) = json(r.text)["reason"]?.jsonPrimitive?.content
    private fun outcome(r: io.androllm.core.tools.remote.RemoteDataResult) = json(r.text)["outcome"]!!.jsonPrimitive.content

    @Test
    fun `run of a trusted OK playbook happens inside a remote origin and returns ids and counts only`() = runTest {
        put(PlaybookFixtures.searchPlaybook())
        val r = handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search"))
        assertThat(r.ok).isTrue()
        assertThat(runs).containsExactly("$PKG/search" to true)
        assertThat(outcome(r)).isEqualTo("ok")
        assertThat(r.text).doesNotContain("cats")
    }

    @Test
    fun `gate B1 - import allowed but run setting off refuses with disabled`() = runTest {
        put(PlaybookFixtures.searchPlaybook())
        listOn = true; runOn = false
        val r = handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search"))
        assertThat(r.status).isEqualTo("denied")
        assertThat(reason(r)).isEqualTo("disabled")
        assertThat(runs).isEmpty()
        assertThat(handler.handle(JarvisPlaybookTaskHandler.TYPE_LIST, null).ok).isTrue()
        listOn = false
        assertThat(reason(handler.handle(JarvisPlaybookTaskHandler.TYPE_LIST, null))).isEqualTo("disabled")
    }

    @Test
    fun `gate B1 - the run setting is off by default`() {
        assertThat(io.androllm.core.appatlas.settings.AppAtlasSettings().allowJarvisPlaybookRun).isFalse()
        assertThat(io.androllm.core.appatlas.settings.AppAtlasSettings().acceptRemotePlaybooks).isTrue()
    }

    @Test
    fun `refusals carry machine reason codes and never run`() = runTest {
        put(PlaybookFixtures.searchPlaybook(), verified = 0)
        assertThat(reason(handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search")))).isEqualTo("not_trusted")

        put(PlaybookFixtures.searchPlaybook(), status = PlaybookStatus.STALE)
        assertThat(reason(handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search")))).isEqualTo("status_stale")

        put(PlaybookFixtures.searchPlaybook(versionMin = 1, versionMax = 2))
        assertThat(reason(handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search")))).isEqualTo("status_unverified")

        val wa = PlaybookFixtures.searchPlaybook().copy(id = "com.whatsapp/search", packageName = "com.whatsapp")
        put(wa, source = PlaybookRepository.SOURCE_FILE)
        assertThat(reason(handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("com.whatsapp/search")))).isEqualTo("protected")

        assertThat(reason(handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/nope")))).isEqualTo("playbook_not_found")
        assertThat(runs).isEmpty()
    }

    @Test
    fun `gate N3 - owner using the phone answers busy without running`() = runTest {
        put(PlaybookFixtures.searchPlaybook())
        busy = "owner_active"
        val r = handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search"))
        assertThat(r.status).isEqualTo("busy")
        assertThat(reason(r)).isEqualTo("owner_active")
        assertThat(runs).isEmpty()
    }

    @Test
    fun `gate B4 - missing or past deadline never starts`() = runTest {
        put(PlaybookFixtures.searchPlaybook())
        assertThat(handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search", deadline = null)).status).isEqualTo("invalid")
        val past = handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search", deadline = now - 1))
        assertThat(outcome(past)).isEqualTo("timeout")
        assertThat(reason(past)).isEqualTo("deadline")
        assertThat(runs).isEmpty()
    }

    @Test
    fun `gate B4 - two risky steps, the second card is cut at the deadline and nothing is tapped after it`() = runTest {
        val screen = NodeSnapshot(PKG, listOf(node(0, "comment_edit", editable = true), node(1, "send_button", text = "Send")))
        val fake = FakeDriver { screen }
        val slow = object : PlaybookDriver by fake {
            override suspend fun confirmTap(summary: String): Boolean { delay(200_000L); return fake.confirmTap(summary) }
        }
        val twoCards = Playbook(
            id = "$PKG/double_send", packageName = PKG, name = "double_send", description = "Two sends",
            versionMin = 1, versionMax = 999, risk = Playbook.RISK_CONFIRM,
            steps = listOf(
                PlaybookStep(op = "tap", locators = listOf(Locator("id", "send_button")), risky = true),
                PlaybookStep(op = "tap", locators = listOf(Locator("id", "send_button")), risky = true)
            )
        )
        put(twoCards, source = PlaybookRepository.SOURCE_FILE)
        val repo = PlaybookRepository(store) { emptySet() }
        val runner = PlaybookRunner(repo, slow, { 150L }, { "Example Video" }, fallback = null)
        val h = JarvisPlaybookTaskHandler(
            repository = repo,
            runPlaybook = { id, args -> runner.run(id, args, allowFallback = false, requireTrustedOk = true) },
            installedVersion = { 150L }, appLabel = { "Example Video" }, atlasSensitive = { false },
            listEnabled = { true }, runEnabled = { true }, ownPackage = "io.androllm.app",
            clock = { testScheduler.currentTime }
        )
        val start = testScheduler.currentTime
        val r = h.handle(JarvisPlaybookTaskHandler.TYPE_RUN, buildJsonObject {
            put("id", twoCards.id); put("deadline_ms", start + 300_000L)
        })
        assertThat(outcome(r)).isEqualTo("timeout")
        assertThat(reason(r)).isEqualTo("deadline")
        assertThat(fake.taps).hasSize(1)                 // first card approved at 200 s, second cut at 300 s
        assertThat(testScheduler.currentTime - start).isAtMost(300_000L)
    }

    @Test
    fun `declined card maps to denied, a decline at the card timeout to timeout`() = runTest {
        put(PlaybookFixtures.sendPlaybook().copy(risk = Playbook.RISK_CONFIRM))
        result = ToolResult.Failure("The user declined the action.", retryable = false)
        val denied = handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/share_note"))
        assertThat(denied.status).isEqualTo("denied")
        assertThat(outcome(denied)).isEqualTo("denied")

        runTakesMs = 300_000L
        val timeout = handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/share_note"))
        assertThat(timeout.status).isEqualTo("denied")
        assertThat(outcome(timeout)).isEqualTo("timeout")
    }

    @Test
    fun `failure data keeps only machine fields and a runner refusal under the mutex keeps its code`() = runTest {
        put(PlaybookFixtures.searchPlaybook())
        result = ToolResult.Failure("Playbook stopped", buildJsonObject {
            put("status", "failed"); put("failed_step", 1); put("reason", "target_not_found"); put("secret_screen_text", "x")
        }, retryable = false)
        val r = handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search"))
        assertThat(r.status).isEqualTo("error")
        val o = json(r.text)
        assertThat(o["outcome"]!!.jsonPrimitive.content).isEqualTo("failed")
        assertThat(o.keys).doesNotContain("secret_screen_text")
        assertThat(o["reason"]!!.jsonPrimitive.content).isEqualTo("target_not_found")

        result = ToolResult.Failure("not trusted on this device", buildJsonObject { put("status", "refused"); put("reason", "not_trusted") }, retryable = false)
        val toctou = handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search"))
        assertThat(outcome(toctou)).isEqualTo("refused")
        assertThat(reason(toctou)).isEqualTo("not_trusted")
    }

    @Test
    fun `list skips protected and not-installed apps and reports status and trust`() = runTest {
        put(PlaybookFixtures.searchPlaybook())
        put(PlaybookFixtures.searchPlaybook().copy(id = "com.whatsapp/search", packageName = "com.whatsapp"))
        put(PlaybookFixtures.searchPlaybook().copy(id = "com.example.gone/search", packageName = "com.example.gone"))
        val r = handler.handle(JarvisPlaybookTaskHandler.TYPE_LIST, null)
        assertThat(r.ok).isTrue()
        val arr = json(r.text)["playbooks"]!!.jsonArray
        assertThat(arr).hasSize(1)
        val e = arr[0].jsonObject
        assertThat(e["id"]!!.jsonPrimitive.content).isEqualTo("$PKG/search")
        assertThat(e["status"]!!.jsonPrimitive.content).isEqualTo("ok")
        assertThat((e["trusted"] as JsonPrimitive).content).isEqualTo("true")
    }
}
