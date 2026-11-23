package io.androllm.core.appatlas

import com.google.common.truth.Truth.assertThat
import io.androllm.core.appatlas.model.ActionKind
import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.model.AtlasAction
import io.androllm.core.appatlas.model.LearnedPath
import io.androllm.core.appatlas.model.PathStep
import io.androllm.core.appatlas.search.AtlasSearch
import io.androllm.core.appatlas.search.Concepts
import io.androllm.core.appatlas.tools.ActionGuard
import org.junit.Test

class GuardAndSearchTest {

    private val open = AtlasAction("open", ActionKind.LAUNCH, "Open")
    private val link = AtlasAction(
        "link:1", ActionKind.DEEP_LINK, "Results", uri = "https://video.example.com/results",
        scheme = "https", host = "video.example.com", pathPrefix = "/results"
    )
    private val custom = AtlasAction("link:2", ActionKind.DEEP_LINK, "Watch", uri = "examplevideo://watch/v/", scheme = "examplevideo", host = "watch")
    private val search = AtlasAction("search", ActionKind.SEARCH, "Search in Example Video", component = "com.example.video/.Search")
    private val hidden = AtlasAction("shortcut:upload", ActionKind.SHORTCUT, "Upload", fireable = false)

    private val video = AppAtlasEntry(
        packageName = "com.example.video", label = "Example Video", versionCode = 420,
        actions = listOf(open, search, link, custom, hidden),
        uiIds = listOf("search_button", "menu_settings"), uiStrings = listOf("Search", "Settings")
    )
    private val bank = AppAtlasEntry(
        packageName = "com.example.mybank", label = "My Bank", versionCode = 3, sensitive = true,
        sensitiveReason = "banking/payment app — open only", actions = listOf(open, link)
    )

    // ── ActionGuard ──

    @Test
    fun `deep link uri must keep scheme and host`() {
        assertThat(ActionGuard.check(video, link, "https://video.example.com/results?search_query=cats", null))
            .isEqualTo(ActionGuard.Verdict.Allowed("https://video.example.com/results?search_query=cats"))
        assertThat(ActionGuard.check(video, link, null, null)).isEqualTo(ActionGuard.Verdict.Allowed(link.uri))
        assertThat(ActionGuard.check(video, link, "https://evil.example.net/results", null)).isInstanceOf(ActionGuard.Verdict.Refused::class.java)
        assertThat(ActionGuard.check(video, link, "http://video.example.com/results", null)).isInstanceOf(ActionGuard.Verdict.Refused::class.java)
        assertThat(ActionGuard.check(video, link, "https://user@video.example.com/x", null)).isInstanceOf(ActionGuard.Verdict.Refused::class.java)
        assertThat(ActionGuard.check(video, link, "intent://video.example.com#Intent;end", null)).isInstanceOf(ActionGuard.Verdict.Refused::class.java)
        assertThat(ActionGuard.check(video, link, "https://video.example.com/a b", null)).isInstanceOf(ActionGuard.Verdict.Refused::class.java)
        assertThat(ActionGuard.check(video, custom, "examplevideo://watch/v/abc123", null)).isInstanceOf(ActionGuard.Verdict.Allowed::class.java)
        assertThat(ActionGuard.check(video, custom, "javascript:alert(1)", null)).isInstanceOf(ActionGuard.Verdict.Refused::class.java)
    }

    @Test
    fun `sensitive apps allow only open and non-fireable actions are refused`() {
        assertThat(ActionGuard.check(bank, open, null, null)).isInstanceOf(ActionGuard.Verdict.Allowed::class.java)
        val refused = ActionGuard.check(bank, link, null, null)
        assertThat(refused).isInstanceOf(ActionGuard.Verdict.Refused::class.java)
        assertThat((refused as ActionGuard.Verdict.Refused).reason).contains("only action_id=open")
        assertThat(ActionGuard.check(video, hidden, null, null)).isInstanceOf(ActionGuard.Verdict.Refused::class.java)
        assertThat(ActionGuard.check(video, search, null, "x".repeat(ActionGuard.MAX_QUERY_CHARS + 1)))
            .isInstanceOf(ActionGuard.Verdict.Refused::class.java)
    }

    // ── AtlasSearch ──

    private val others = (1..30).map { i ->
        AppAtlasEntry("com.example.app$i", "Tool $i", 1, actions = listOf(open), uiStrings = listOf("Search"))
    }

    @Test
    fun `label match wins and search action ranks first`() {
        val hits = AtlasSearch.search(listOf(video, bank) + others, "open example video and search cats")
        assertThat(hits.first().entry.packageName).isEqualTo("com.example.video")
        assertThat(hits.first().actions.first().id).isEqualTo("search")
        assertThat(hits.all { it.labelMatched }).isTrue()
    }

    @Test
    fun `cyrillic and transliterated app names match`() {
        val tg = AppAtlasEntry("org.example.telegram", "Telegram", 1, actions = listOf(open))
        val yt = AppAtlasEntry("com.example.youtube", "YouTube", 1, actions = listOf(open))
        assertThat(AtlasSearch.search(listOf(tg, yt), "открой телеграм").first().entry.label).isEqualTo("Telegram")
        assertThat(AtlasSearch.search(listOf(tg, yt), "найди в ютуб котиков").first().entry.label).isEqualTo("YouTube")
        assertThat(AtlasSearch.search(listOf(tg, yt), "telegrami aç").first().entry.label).isEqualTo("Telegram")
    }

    @Test
    fun `sensitive app is rendered as no-automation with open only`() {
        val hits = AtlasSearch.search(listOf(bank, video), "my bank")
        val text = AtlasSearch.render(hits, "my bank")
        assertThat(hits.first().entry.packageName).isEqualTo("com.example.mybank")
        assertThat(hits.first().actions.map { it.id }).containsExactly("open")
        assertThat(text).contains("NO AUTOMATION")
        assertThat(text).doesNotContain("https://video.example.com")
    }

    @Test
    fun `learned paths and ui hints are shown, output is capped`() {
        val p = LearnedPath("com.example.video", "search", 419, listOf(PathStep("tap", id = "search_button"), PathStep("type", id = "query")), verified = true)
        val hits = AtlasSearch.search(listOf(video), "example video search", mapOf("com.example.video" to listOf(p)))
        val text = AtlasSearch.render(hits, "example video search")
        assertThat(text).contains("#search_button")
        assertThat(text).contains("learned search: tap #search_button → type #query {text}")
        assertThat(text).contains("stale") // recorded on 419, app is 420

        val many = (1..50).map { i ->
            AppAtlasEntry("com.example.v$i", "Video $i", 1, actions = (1..10).map { j ->
                AtlasAction("link:$j", ActionKind.DEEP_LINK, "Video search page $j", uri = "https://v$i.example.com/search/$j", scheme = "https", host = "v$i.example.com")
            })
        }
        val big = AtlasSearch.render(AtlasSearch.search(many, "video search"), "video search")
        assertThat(big.length).isAtMost(AtlasSearch.MAX_CHARS + 40)
    }

    @Test
    fun `no match gives a helpful fallback`() {
        val text = AtlasSearch.render(AtlasSearch.search(listOf(video), "zzzz qqqq"), "zzzz qqqq")
        assertThat(text).contains("launch_app")
    }

    @Test
    fun `concepts are multilingual`() {
        assertThat(Concepts.of("найди видео")).containsAtLeast("search", "video")
        assertThat(Concepts.of("mahnı oxut")).containsAtLeast("music", "play")
        assertThat(Concepts.taskKey("hello there")).isNull()
    }
}
