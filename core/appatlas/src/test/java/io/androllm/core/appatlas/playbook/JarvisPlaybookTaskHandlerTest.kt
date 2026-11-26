package io.androllm.core.appatlas.playbook

import com.google.common.truth.Truth.assertThat
import io.androllm.core.appatlas.playbook.PlaybookFixtures.PKG
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.origin.currentRemoteOrigin
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
    private var now = 0L
    private val runs = mutableListOf<Pair<String, Boolean>>() // id to "ran inside a remote origin"
    private var result: ToolResult = ToolResult.Success("Done", buildJsonObject { put("status", "ok"); put("verified", true); put("steps", 3) })
    private var runTakesMs = 10L
    private var enabled = true
    private val installed = mutableMapOf(PKG to 150L, "com.whatsapp" to 1L)

    // A fresh repository per call: PlaybookRepository caches store.all() and the tests edit the store directly.
    private val handler get() = JarvisPlaybookTaskHandler(
        repository = PlaybookRepository(store) { emptySet() },
        runPlaybook = { id, _ -> runs += id to (currentRemoteOrigin() != null); now += runTakesMs; result },
        installedVersion = { installed[it] },
        appLabel = { if (it == PKG) "Example Video" else null },
        atlasSensitive = { false },
        enabled = { enabled },
        ownPackage = "io.androllm.app",
        clock = { now },
        confirmTimeoutMs = 300_000L
    )

    private fun put(p: Playbook, source: String = PlaybookRepository.SOURCE_JARVIS, verified: Int = 1, status: PlaybookStatus = PlaybookStatus.OK) {
        store.items[p.id] = StoredPlaybook(p, status = status, source = source, verifiedCount = verified, flaggedVersion = if (status == PlaybookStatus.STALE) 150L else null)
    }

    private fun runBody(id: String) = buildJsonObject { put("id", id); putJsonObject("args") { put("query", "cats") } }
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `run of a trusted OK playbook happens inside a remote origin and returns ids and counts only`() = runTest {
        put(PlaybookFixtures.searchPlaybook())
        val r = handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search"))
        assertThat(r.ok).isTrue()
        assertThat(runs).containsExactly("$PKG/search" to true)
        val o = json(r.text)
        assertThat(o["outcome"]!!.jsonPrimitive.content).isEqualTo("ok")
        assertThat(r.text).doesNotContain("cats")
    }

    @Test
    fun `untrusted, stale, protected or missing playbooks are refused without running`() = runTest {
        put(PlaybookFixtures.searchPlaybook(), verified = 0)
        val untrusted = handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search"))
        assertThat(untrusted.status).isEqualTo("denied")
        assertThat(untrusted.text).contains("not verified")

        put(PlaybookFixtures.searchPlaybook(), status = PlaybookStatus.STALE)
        assertThat(handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search")).text).contains("status stale")

        put(PlaybookFixtures.searchPlaybook(versionMin = 1, versionMax = 2))
        assertThat(handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search")).text).contains("status unverified")

        val wa = PlaybookFixtures.searchPlaybook().copy(id = "com.whatsapp/search", packageName = "com.whatsapp")
        put(wa, source = PlaybookRepository.SOURCE_FILE)
        assertThat(handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("com.whatsapp/search")).text).contains("protected")

        assertThat(handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/nope")).text).contains("no such playbook")
        assertThat(runs).isEmpty()
    }

    @Test
    fun `declined card maps to denied, a decline at the card timeout to timeout`() = runTest {
        put(PlaybookFixtures.sendPlaybook().copy(risk = Playbook.RISK_CONFIRM))
        result = ToolResult.Failure("The user declined the action.", retryable = false)
        val denied = handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, buildJsonObject { put("id", "$PKG/share_note") })
        assertThat(denied.status).isEqualTo("denied")
        assertThat(json(denied.text)["outcome"]!!.jsonPrimitive.content).isEqualTo("denied")

        runTakesMs = 300_000L
        val timeout = handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, buildJsonObject { put("id", "$PKG/share_note") })
        assertThat(timeout.status).isEqualTo("denied")
        assertThat(json(timeout.text)["outcome"]!!.jsonPrimitive.content).isEqualTo("timeout")
    }

    @Test
    fun `failure data keeps only machine fields`() = runTest {
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

    @Test
    fun `disabled setting refuses both types`() = runTest {
        enabled = false
        put(PlaybookFixtures.searchPlaybook())
        assertThat(handler.handle(JarvisPlaybookTaskHandler.TYPE_LIST, null).status).isEqualTo("denied")
        assertThat(handler.handle(JarvisPlaybookTaskHandler.TYPE_RUN, runBody("$PKG/search")).status).isEqualTo("denied")
        assertThat(runs).isEmpty()
    }
}
