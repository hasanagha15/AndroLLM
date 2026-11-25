package io.androllm.core.appatlas.playbook

import com.google.common.truth.Truth.assertThat
import io.androllm.core.appatlas.playbook.PlaybookFixtures.bundleJson
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SensitivePlaybookRejectionTest {

    private fun pb(pkg: String, label: String? = null) =
        """{"id":"$pkg/open_home","package":"$pkg","name":"open_home",${label?.let { "\"appLabel\":\"$it\"," } ?: ""}
           "description":"Open home","versionMin":1,"versionMax":9,"steps":[{"op":"back"}]}"""

    @Test
    fun `guard list, owner extras and the banking heuristic are never imported`() {
        val cases = listOf(
            pb("com.whatsapp"),
            pb("com.whatsapp.w4b"),
            pb("com.teslamotors.tesla"),
            pb("az.kapitalbank.mbanking"),          // built-in banking list (K)
            pb("com.example.mybank"),               // heuristic: bank
            pb("com.example.quickpay"),             // heuristic: *pay
            pb("com.example.notes", "Crypto Wallet"), // heuristic via appLabel
            pb("com.example.owned")                 // owner's SensitiveAppStore extra
        )
        val r = PlaybookValidator.parseBundle(bundleJson(cases.joinToString(",")), extras = setOf("com.example.owned"))
        assertThat(r.accepted).isEmpty()
        assertThat(r.rejected).hasSize(cases.size)
        assertThat(r.rejected.all { it.reason.startsWith("protected app") }).isTrue()
    }

    @Test
    fun `installed label from the atlas is also checked`() {
        val r = PlaybookValidator.parseBundle(bundleJson(pb("com.example.app")), emptySet()) { "Birbank" }
        assertThat(r.accepted).isEmpty()
    }

    @Test
    fun `repository stores only the safe playbooks`() = runTest {
        val store = MemoryPlaybookStore()
        val repo = PlaybookRepository(store) { setOf("com.example.owned") }
        val report = repo.importJson(bundleJson(listOf(pb("com.whatsapp"), pb("com.example.owned"), pb("com.example.notes")).joinToString(",")), "jarvis", null)
        assertThat(report.accepted.map { it.packageName }).containsExactly("com.example.notes")
        assertThat(store.items.keys).containsExactly("com.example.notes/open_home")
    }

    @Test
    fun `playbooks of an app that becomes protected are hidden`() = runTest {
        val store = MemoryPlaybookStore()
        var extras = emptySet<String>()
        val repo = PlaybookRepository(store) { extras }
        repo.importJson(bundleJson(pb("com.example.notes")), "file", null)
        assertThat(repo.forPackage("com.example.notes")).hasSize(1)
        extras = setOf("com.example.notes")
        assertThat(repo.forPackage("com.example.notes")).isEmpty()
    }
}
