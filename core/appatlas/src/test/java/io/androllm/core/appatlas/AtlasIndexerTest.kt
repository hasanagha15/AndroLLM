package io.androllm.core.appatlas

import com.google.common.truth.Truth.assertThat
import io.androllm.core.appatlas.index.AtlasIndexer
import io.androllm.core.appatlas.index.SensitiveClassifier
import io.androllm.core.appatlas.model.ActionKind
import io.androllm.core.appatlas.model.DataSpec
import io.androllm.core.appatlas.model.ParsedComponent
import io.androllm.core.appatlas.model.ParsedFilter
import io.androllm.core.appatlas.model.ParsedManifest
import io.androllm.core.appatlas.parse.ManifestIntentParser
import io.androllm.core.appatlas.parse.ShortcutXmlParser
import org.junit.Test

class AtlasIndexerTest {

    private val video = Fixtures.basics("com.example.video", "Example Video", version = 420)

    private fun videoSource() = FakePackageSource(packages = mutableListOf(video)).apply {
        manifests["com.example.video"] = ManifestIntentParser.parse(
            Fixtures.parser(Fixtures.VIDEO_MANIFEST), Fixtures.resolver(Fixtures.STRINGS), "com.example.video"
        )!!
        shortcutsByPkg["com.example.video"] =
            ShortcutXmlParser.parse(Fixtures.parser(Fixtures.VIDEO_SHORTCUTS), Fixtures.resolver(Fixtures.STRINGS))
        vocabularyByPkg["com.example.video"] = listOf("search_button", "send") to listOf("Search", "Settings", "Search")
    }

    @Test
    fun `deep links come only from startable activities with a scheme`() {
        val e = AtlasIndexer(videoSource()).index(video)
        val links = e.actions.filter { it.kind == ActionKind.DEEP_LINK }
        val uris = links.map { it.uri }
        assertThat(uris).containsAtLeast(
            "https://video.example.com/results",
            "examplevideo://watch/v/",
            "examplevideo://home"
        )
        assertThat(uris.none { it!!.startsWith("internal-secret") }).isTrue() // not exported
        assertThat(uris.none { it!!.startsWith("protectedlink") }).isTrue() // permission-protected
        assertThat(links.all { it.id.startsWith("link:") }).isTrue()
        assertThat(links.first { it.uri == "https://video.example.com/results" }.host).isEqualTo("video.example.com")
    }

    @Test
    fun `link ids are deterministic for the same app version`() {
        val a = AtlasIndexer(videoSource(), clock = { 1L }).index(video)
        val b = AtlasIndexer(videoSource(), clock = { 1L }).index(video)
        assertThat(a.actions).isEqualTo(b.actions)
    }

    @Test
    fun `open, search and shortcuts with fireability`() {
        val e = AtlasIndexer(videoSource()).index(video)
        assertThat(e.actions.first().id).isEqualTo("open")
        val search = e.action("search")!!
        assertThat(search.kind).isEqualTo(ActionKind.SEARCH)
        assertThat(search.component).isEqualTo("com.example.video/com.example.video.ui.SearchActivity")
        assertThat(e.action("shortcut:search")!!.fireable).isTrue()
        val upload = e.action("shortcut:upload")!!
        assertThat(upload.fireable).isFalse() // UploadActivity is not exported
        assertThat(upload.extras).containsExactly("mode", "new")
        assertThat(e.action("shortcut:disabled_one")).isNull()
        assertThat(e.action("shortcut:other_app")).isNull() // never targets another package
        assertThat(e.manifestParsed).isTrue()
    }

    @Test
    fun `vocabulary is deduplicated`() {
        val e = AtlasIndexer(videoSource()).index(video)
        assertThat(e.uiIds).containsExactly("search_button", "send")
        assertThat(e.uiStrings).containsExactly("Search", "Settings").inOrder()
    }

    @Test
    fun `sensitive apps are metadata only with just open`() {
        val bank = Fixtures.basics("com.example.mybank", "My Bank")
        val messenger = Fixtures.basics("com.whatsapp", "WhatsApp")
        val src = FakePackageSource(packages = mutableListOf(bank, messenger)).apply {
            vocabularyByPkg["com.example.mybank"] = listOf("pay_button") to listOf("Pay")
        }
        for (b in listOf(bank, messenger)) {
            val e = AtlasIndexer(src).index(b)
            assertThat(e.sensitive).isTrue()
            assertThat(e.sensitiveReason).isNotEmpty()
            assertThat(e.actions.map { it.id }).containsExactly("open")
            assertThat(e.uiIds).isEmpty()
            assertThat(e.uiStrings).isEmpty()
        }
        assertThat(src.manifestCalls).isEqualTo(0) // never even reads their manifest
    }

    @Test
    fun `sensitive classifier heuristics`() {
        assertThat(SensitiveClassifier.reason("com.google.android.apps.walletnfcrel", "Wallet")).isNotNull()
        assertThat(SensitiveClassifier.reason("com.example.gpay", "G Pay")).isNotNull()
        assertThat(SensitiveClassifier.reason("com.teslamotors.tesla", "Tesla")).isNotNull()
        assertThat(SensitiveClassifier.reason("com.example.display", "Display Tuner")).isNull()
        assertThat(SensitiveClassifier.reason("com.example.video", "Example Video")).isNull()
    }

    @Test
    fun `size bounds hold for huge apps`() {
        val pkg = "com.example.huge"
        val filters = (1..60).map { i ->
            ParsedFilter(
                actions = setOf("android.intent.action.VIEW"),
                categories = setOf("android.intent.category.BROWSABLE"),
                data = listOf(DataSpec(scheme = "huge$i", host = "h$i", path = "/p$i"))
            )
        }
        val components = (1..40).map { c ->
            ParsedComponent("$pkg.A$c", "A$c", exported = true, enabled = true, permission = null, filters = filters.take(12), shortcutsResource = null)
        }
        val src = FakePackageSource(packages = mutableListOf(Fixtures.basics(pkg, "Huge"))).apply {
            manifests[pkg] = ParsedManifest(pkg, 1, components)
            vocabularyByPkg[pkg] = (1..200).map { "id_$it" } to (1..200).map { "String number $it" } + listOf("x".repeat(500), "multi\nline ok")
        }
        val e = AtlasIndexer(src).index(src.packages.first())
        assertThat(e.actions.size).isAtMost(AtlasIndexer.MAX_ACTIONS)
        assertThat(e.actions.count { it.kind == ActionKind.DEEP_LINK }).isAtMost(AtlasIndexer.MAX_DEEP_LINKS)
        assertThat(e.uiIds.size).isAtMost(AtlasIndexer.MAX_UI_IDS)
        assertThat(e.uiStrings.size).isAtMost(AtlasIndexer.MAX_UI_STRINGS)
        assertThat(e.uiStrings.all { it.length <= AtlasIndexer.MAX_STRING && '\n' !in it }).isTrue()
        assertThat(e.actions.all { (it.uri?.length ?: 0) <= AtlasIndexer.MAX_URI && it.title.length <= AtlasIndexer.MAX_TITLE }).isTrue()
    }

    @Test
    fun `fallback without manifest uses search activities and launcher shortcuts`() {
        val pkg = "com.example.closed"
        val src = FakePackageSource(packages = mutableListOf(Fixtures.basics(pkg, "Closed"))).apply {
            searchByPkg[pkg] = listOf("$pkg.Find")
            shortcutsByPkg[pkg] = ShortcutXmlParser.parse(
                Fixtures.parser(
                    """<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
                      <shortcut android:shortcutId="new" android:shortcutShortLabel="New note">
                        <intent android:action="android.intent.action.VIEW" android:targetPackage="$pkg" android:targetClass="$pkg.Edit" />
                      </shortcut></shortcuts>"""
                ),
                Fixtures.resolver(emptyMap())
            )
        }
        val e = AtlasIndexer(src).index(src.packages.first())
        assertThat(e.manifestParsed).isFalse()
        assertThat(e.action("search")!!.component).isEqualTo("$pkg/$pkg.Find")
        assertThat(e.action("shortcut:new")!!.title).isEqualTo("New note")
        assertThat(e.actions.none { it.kind == ActionKind.DEEP_LINK }).isTrue()
    }

    @Test
    fun `uri helpers`() {
        assertThat(AtlasIndexer.literalPrefix("/v/.*")).isEqualTo("/v/")
        assertThat(AtlasIndexer.buildUri("app", null, null)).isEqualTo("app:")
        assertThat(AtlasIndexer.buildUri("https", "x.com", "a")).isEqualTo("https://x.com/a")
        assertThat(AtlasIndexer.humanize("com.x.ComposeMessageActivity")).isEqualTo("Compose message")
    }
}
