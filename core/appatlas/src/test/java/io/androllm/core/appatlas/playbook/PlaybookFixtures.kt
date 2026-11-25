package io.androllm.core.appatlas.playbook

import io.androllm.core.accessibility.controller.NodeRef
import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.accessibility.controller.RiskApproval
import io.androllm.core.accessibility.controller.RiskyElementGate
import io.androllm.core.accessibility.controller.ScreenNode
import io.androllm.core.accessibility.controller.UiActionResult
import io.androllm.core.appatlas.store.PlaybookStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Synthetic test data only (com.example.* packages). */
object PlaybookFixtures {

    const val PKG = "com.example.video"

    fun node(
        index: Int,
        id: String? = null,
        text: String? = null,
        desc: String? = null,
        cls: String = "android.widget.TextView",
        classIndex: Int = 0,
        clickable: Boolean = true,
        editable: Boolean = false,
        pkg: String = PKG
    ) = ScreenNode(
        index = index, viewId = id?.let { "$pkg:id/$it" }, text = text, description = desc, className = cls,
        classIndex = classIndex, clickable = clickable, editable = editable,
        left = 0.1f, top = 0.1f * index, right = 0.9f, bottom = 0.1f * index + 0.05f
    )

    fun searchPlaybook(
        versionMin: Long = 100, versionMax: Long = 200,
        postcondition: Expect? = Expect(ids = listOf("results_list"))
    ) = Playbook(
        id = "$PKG/search",
        packageName = PKG,
        name = "search",
        appLabel = "Example Video",
        description = "Search videos",
        params = listOf(PlaybookParam("query", description = "what to search")),
        versionMin = versionMin,
        versionMax = versionMax,
        triggers = listOf("найди {query} в видео", "search video for {query}", "naydi {query} v video"),
        steps = listOf(
            PlaybookStep(op = "tap", locators = listOf(Locator("id", "search_button"), Locator("desc", "Search")), expect = Expect(ids = listOf("toolbar"))),
            PlaybookStep(op = "type", locators = listOf(Locator("id", "search_edit")), text = "{query}"),
            PlaybookStep(op = "tap", locators = listOf(Locator("id", "go_button")))
        ),
        postcondition = postcondition
    )

    fun sendPlaybook() = Playbook(
        id = "$PKG/share_note",
        packageName = PKG,
        name = "share_note",
        description = "Post a comment",
        params = listOf(PlaybookParam("text")),
        versionMin = 1, versionMax = 999,
        steps = listOf(
            PlaybookStep(op = "type", locators = listOf(Locator("id", "comment_edit")), text = "{text}"),
            PlaybookStep(op = "tap", locators = listOf(Locator("id", "send_button"), Locator("text", "Send")))
        )
    )

    fun bundleJson(body: String) =
        """{"format":"androllm.playbooks","schemaVersion":1,"playbooks":[$body]}"""

    const val VALID_PLAYBOOK_JSON = """{
      "id":"com.example.video/search","package":"com.example.video","name":"search",
      "description":"Search videos","params":[{"name":"query","type":"text"}],
      "versionMin":100,"versionMax":200,"triggers":["найди {query} в видео"],
      "steps":[
        {"op":"open_deeplink","uri":"examplevideo://search?q={query}","timeoutMs":3000},
        {"op":"tap","locators":[{"kind":"id","value":"search_button"},{"kind":"bounds","bounds":[0.8,0.0,1.0,0.1]}],"expect":{"ids":["toolbar"]}},
        {"op":"type","locators":[{"kind":"id","value":"search_edit"}],"text":"{query}"},
        {"op":"scroll","direction":"down"},
        {"op":"wait","waitMs":200},
        {"op":"back"}
      ],
      "postcondition":{"ids":["results_list"]}
    }"""
}

/** In-memory [PlaybookStore]. */
class MemoryPlaybookStore : PlaybookStore {
    val items = LinkedHashMap<String, StoredPlaybook>()
    val relearn = LinkedHashMap<Pair<String, Long>, Pair<RelearnRequest, Boolean>>()

    override suspend fun all() = items.values.toList()
    override suspend fun forPackage(packageName: String) = items.values.filter { it.playbook.packageName == packageName }
    override suspend fun get(id: String) = items[id]
    override suspend fun upsert(items: List<StoredPlaybook>) { items.forEach { this.items[it.playbook.id] = it } }
    override suspend fun delete(ids: Collection<String>) { ids.forEach { items.remove(it) } }
    override suspend fun count() = items.size
    override suspend fun relearn(id: String, versionCode: Long) = relearn[id to versionCode]?.first
    override suspend fun addRelearn(request: RelearnRequest) { relearn[request.playbookId to request.versionCode] = request to false }
    override suspend fun pendingRelearn(limit: Int) = relearn.values.filter { !it.second }.map { it.first }.take(limit)
    override suspend fun markDelivered(requests: List<RelearnRequest>) {
        requests.forEach { r -> relearn[r.playbookId to r.versionCode]?.let { relearn[r.playbookId to r.versionCode] = it.first to true } }
    }
    override suspend fun pruneDelivered(before: Long) = Unit
}

/**
 * Scripted device: [screen] decides what is visible (may change after actions).
 * Records taps / typed text / confirmations.
 */
class FakeDriver(var screen: () -> NodeSnapshot?) : PlaybookDriver {
    val events = MutableStateFlow(0L)
    override val uiEvents: StateFlow<Long> = events
    val taps = mutableListOf<Pair<NodeRef, RiskApproval?>>()
    val typed = mutableListOf<String>()
    val confirmations = mutableListOf<String>()
    var approve = true
    var launches = 0
    var deeplinks = mutableListOf<String>()
    /** Risk decision of the "controller" for a tapped node (branch M gate). */
    var gate: (ScreenNode, RiskApproval?) -> RiskyElementGate.Decision = { n, a ->
        RiskyElementGate.decide(RiskyElementGate.Facts(n.text, n.description, n.viewId), a)
    }
    var onTap: (NodeRef) -> Unit = {}

    override suspend fun snapshot(): NodeSnapshot? = screen()

    override suspend fun tap(ref: NodeRef, approval: RiskApproval?): UiActionResult {
        val n = screen()?.nodes?.firstOrNull { it.index == ref.index } ?: return UiActionResult.fail("gone")
        (gate(n, approval) as? RiskyElementGate.Decision.Refuse)?.let { return UiActionResult.risky(it, "playbook_run") }
        taps += ref to approval
        onTap(ref)
        events.value++
        return UiActionResult.ok("tapped")
    }

    override suspend fun type(ref: NodeRef?, text: String): UiActionResult { typed += text; events.value++; return UiActionResult.ok("typed") }
    override suspend fun scroll(direction: String) = UiActionResult.ok("scrolled")
    override suspend fun back() = UiActionResult.ok("back")
    override suspend fun openDeeplink(pkg: String, uri: String): String? { deeplinks += uri; return null }
    override suspend fun launch(pkg: String): String? { launches++; return null }
    var onConfirm: () -> Unit = {}
    override suspend fun confirmTap(summary: String): Boolean { confirmations += summary; onConfirm(); return approve }
}
