package io.androllm.core.appatlas

import com.google.common.truth.Truth.assertThat
import io.androllm.core.appatlas.parse.ManifestIntentParser
import io.androllm.core.appatlas.parse.ShortcutXmlParser
import org.junit.Test

class ParsersTest {

    private fun manifest() = ManifestIntentParser.parse(
        Fixtures.parser(Fixtures.VIDEO_MANIFEST), Fixtures.resolver(Fixtures.STRINGS), "com.example.video"
    )!!

    @Test
    fun `manifest components, labels and relative names are resolved`() {
        val m = manifest()
        assertThat(m.packageName).isEqualTo("com.example.video")
        assertThat(m.versionCode).isEqualTo(420L)
        val names = m.components.map { it.name }
        assertThat(names).containsAtLeast(
            "com.example.video.MainActivity",
            "com.example.video.ui.SearchActivity",
            "com.example.video.WatchActivity",
            "com.example.video.Launcher2"
        )
        val main = m.components.first { it.name.endsWith(".MainActivity") }
        assertThat(main.label).isEqualTo("Example Video")
        assertThat(main.shortcutsResource).isEqualTo("@xml/shortcuts")
        assertThat(m.components.first { it.name.endsWith("Launcher2") }.isAlias).isTrue()
    }

    @Test
    fun `exported defaults follow intent filters and explicit flags`() {
        val m = manifest()
        val byName = m.components.associateBy { it.name.substringAfterLast('.') }
        assertThat(byName.getValue("SearchActivity").exported).isTrue() // implicit: has filters
        assertThat(byName.getValue("UploadActivity").exported).isFalse() // explicit false, no filters
        assertThat(byName.getValue("InternalActivity").startableByOthers).isFalse()
        assertThat(byName.getValue("ProtectedActivity").startableByOthers).isFalse() // permission-protected
        assertThat(byName.getValue("WatchActivity").startableByOthers).isTrue()
    }

    @Test
    fun `deep link data elements are captured per filter`() {
        val search = manifest().components.first { it.name.endsWith("SearchActivity") }
        val view = search.filters.first { "android.intent.action.VIEW" in it.actions }
        assertThat(view.categories).contains("android.intent.category.BROWSABLE")
        assertThat(view.data.mapNotNull { it.scheme }).containsExactly("https")
        assertThat(view.data.mapNotNull { it.host }).containsExactly("video.example.com")
        assertThat(view.data.mapNotNull { it.pathPrefix }).containsExactly("/results")
    }

    @Test
    fun `manifest of another package is rejected`() {
        val other = ManifestIntentParser.parse(
            Fixtures.parser(Fixtures.VIDEO_MANIFEST), Fixtures.resolver(emptyMap()), "com.example.overlay"
        )
        assertThat(other).isNull()
    }

    @Test
    fun `class name resolution`() {
        assertThat(ManifestIntentParser.resolveClass("a.b", ".X")).isEqualTo("a.b.X")
        assertThat(ManifestIntentParser.resolveClass("a.b", "X")).isEqualTo("a.b.X")
        assertThat(ManifestIntentParser.resolveClass("a.b", "c.d.X")).isEqualTo("c.d.X")
        assertThat(ManifestIntentParser.resolveClass("a.b", " ")).isNull()
    }

    @Test
    fun `shortcut xml is parsed with labels, back stack and literal extras only`() {
        val list = ShortcutXmlParser.parse(Fixtures.parser(Fixtures.VIDEO_SHORTCUTS), Fixtures.resolver(Fixtures.STRINGS))
        assertThat(list.map { it.id }).containsExactly("search", "upload", "disabled_one", "other_app").inOrder()
        val search = list[0]
        assertThat(search.shortLabel).isEqualTo("Search")
        assertThat(search.intents.single().targetClass).isEqualTo("com.example.video.ui.SearchActivity")
        val upload = list[1]
        assertThat(upload.intents).hasSize(2)
        assertThat(upload.intents.last().extras).containsExactly("mode", "new") // @string ref extra dropped
        assertThat(list[2].enabled).isFalse()
    }
}
