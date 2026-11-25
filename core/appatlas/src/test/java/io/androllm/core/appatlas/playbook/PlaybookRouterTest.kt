package io.androllm.core.appatlas.playbook

import com.google.common.truth.Truth.assertThat
import io.androllm.core.appatlas.playbook.PlaybookFixtures.PKG
import io.androllm.core.appatlas.playbook.PlaybookFixtures.node
import org.junit.Test

class PlaybookRouterTest {

    private val search = PlaybookFixtures.searchPlaybook()
    private val notes = Playbook(
        id = "com.example.notes/new_note", packageName = "com.example.notes", name = "new_note",
        description = "New note", versionMin = 1, versionMax = 9,
        triggers = listOf("новая заметка", "new note", "создай заметку"),
        steps = listOf(PlaybookStep(op = "back"))
    )
    private val all = listOf(search, notes)

    @Test
    fun `ru trigger with slot keeps the user's casing`() {
        val m = PlaybookRouter.match("Найди Котиков в видео!", all)!!
        assertThat(m.playbook.id).isEqualTo(search.id)
        assertThat(m.slots["query"]).isEqualTo("Котиков")
    }

    @Test
    fun `en and translit triggers, politeness and punctuation ignored`() {
        assertThat(PlaybookRouter.match("please, search video for lo-fi beats", all)!!.slots["query"]).isEqualTo("lo fi beats")
        assertThat(PlaybookRouter.match("naydi koshek v video pozhaluysta", all)!!.slots["query"]).isEqualTo("koshek")
        assertThat(PlaybookRouter.match("Новая заметка.", all)!!.playbook.id).isEqualTo(notes.id)
        assertThat(PlaybookRouter.match("создай заметку, пожалуйста", all)!!.playbook.id).isEqualTo(notes.id)
    }

    @Test
    fun `ё equals е and multi-word slots work`() {
        val p = notes.copy(params = listOf(PlaybookParam("title")), triggers = listOf("заметка про {title} в блокноте"))
        val m = PlaybookRouter.match("Заметка про ёлку и подарки в блокноте", listOf(p))!!
        assertThat(m.slots["title"]).isEqualTo("елку и подарки")
    }

    @Test
    fun `no partial or loose matches`() {
        assertThat(PlaybookRouter.match("найди в видео", all)).isNull()              // empty slot
        assertThat(PlaybookRouter.match("найди котов ютубе", all)).isNull()          // literal missing
        assertThat(PlaybookRouter.match("ну найди котов в видео сейчас", all)).isNull() // extra words
        assertThat(PlaybookRouter.match("", all)).isNull()
    }

    @Test
    fun `slot validation applies (one line, length)`() {
        assertThat(PlaybookRouter.match("search video for ${"x".repeat(250)}", all)).isNull()
    }

    @Test
    fun `ambiguous different playbooks fall through to the model, more literals win`() {
        val clash = notes.copy(id = "com.example.other/new_note", packageName = "com.example.other")
        assertThat(PlaybookRouter.match("new note", listOf(notes, clash))).isNull()
        val generic = search.copy(id = "$PKG/find", name = "find", triggers = listOf("найди {query}"))
        assertThat(PlaybookRouter.match("найди котиков в видео", listOf(generic, search))!!.playbook.id).isEqualTo(search.id)
    }

    @Test
    fun `locator resolver scores weights, honours min score and strict uniqueness`() {
        val nodes = listOf(
            node(0, "search_button", desc = "Search"),
            node(1, null, desc = "Search"),
            node(2, null, cls = "android.widget.Button")
        )
        val both = listOf(Locator("id", "search_button"), Locator("desc", "Search"))
        val r = LocatorResolver.resolve(both, nodes)!!
        assertThat(r.node.index).isEqualTo(0)
        assertThat(r.score).isWithin(1e-9).of(1.8)
        assertThat(r.matched).containsExactly("id", "desc").inOrder()

        val descOnly = listOf(Locator("desc", "Search"))
        assertThat(LocatorResolver.resolve(descOnly, nodes)!!.node.index).isEqualTo(0) // tie → tree order
        assertThat(LocatorResolver.resolve(descOnly, nodes, strict = true)).isNull()   // tie → strict refuses

        val weak = listOf(Locator("class_index", "Button", index = 0))
        assertThat(LocatorResolver.resolve(weak, nodes)).isNull()                     // 0.4 < 0.7
        assertThat(LocatorResolver.resolve(weak, nodes, minScore = 0.4)!!.node.index).isEqualTo(2)
    }
}
