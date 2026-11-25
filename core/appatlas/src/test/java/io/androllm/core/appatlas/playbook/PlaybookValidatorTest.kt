package io.androllm.core.appatlas.playbook

import com.google.common.truth.Truth.assertThat
import io.androllm.core.appatlas.playbook.PlaybookFixtures.VALID_PLAYBOOK_JSON
import io.androllm.core.appatlas.playbook.PlaybookFixtures.bundleJson
import org.junit.Test

class PlaybookValidatorTest {

    private fun parse(json: String) = PlaybookValidator.parseBundle(json, emptySet(), ownPackage = "io.androllm.app")

    private fun withStep(step: String) = bundleJson(
        """{"id":"com.example.video/x","package":"com.example.video","name":"x","description":"d",
           "versionMin":1,"versionMax":2,"params":[{"name":"q"}],"steps":[$step]}"""
    )

    @Test
    fun `valid bundle with every op is accepted`() {
        val r = parse(bundleJson(VALID_PLAYBOOK_JSON))
        assertThat(r.rejected).isEmpty()
        val p = r.accepted.single()
        assertThat(p.steps.map { it.op }).containsExactly("open_deeplink", "tap", "type", "scroll", "wait", "back").inOrder()
        assertThat(p.risk).isEqualTo(Playbook.RISK_NONE)
        assertThat(p.postcondition!!.ids).containsExactly("results_list")
    }

    @Test
    fun `unknown op is rejected`() {
        val r = parse(withStep("""{"op":"shell","text":"x"}"""))
        assertThat(r.accepted).isEmpty()
        assertThat(r.rejected.single().reason).contains("unknown op")
    }

    @Test
    fun `unknown field rejects the bundle (strict schema)`() {
        val r = parse(withStep("""{"op":"back","exec":"rm -rf"}"""))
        assertThat(r.accepted).isEmpty()
        assertThat(r.rejected.single().ref).isEqualTo("bundle")
    }

    @Test
    fun `wrong format or schema version is rejected`() {
        assertThat(parse("""{"format":"x","schemaVersion":1,"playbooks":[]}""").rejected.single().reason).contains("format")
        assertThat(parse("""{"format":"androllm.playbooks","schemaVersion":2,"playbooks":[]}""").rejected.single().reason).contains("schemaVersion")
        assertThat(parse("not json").accepted).isEmpty()
    }

    @Test
    fun `sizes are capped`() {
        val many = (0..PlaybookValidator.MAX_STEPS).joinToString(",") { """{"op":"back"}""" }
        assertThat(parse(withStep(many)).rejected.single().reason).contains("steps must be")
        val huge = "x".repeat(PlaybookValidator.MAX_BUNDLE_CHARS + 1)
        assertThat(parse(huge).rejected.single().reason).contains("larger than")
        val longDesc = bundleJson(
            """{"id":"com.example.video/x","package":"com.example.video","name":"x","description":"${"d".repeat(121)}",
               "versionMin":1,"versionMax":2,"steps":[{"op":"back"}]}"""
        )
        assertThat(parse(longDesc).rejected.single().reason).contains("description")
        assertThat(parse(withStep("""{"op":"back","timeoutMs":999999}""")).rejected.single().reason).contains("timeoutMs")
        assertThat(parse(withStep("""{"op":"tap","locators":[${(0..6).joinToString(",") { """{"kind":"id","value":"a$it"}""" }}]}""")).rejected.single().reason)
            .contains("locators")
    }

    @Test
    fun `fields that do not belong to the op and malformed locators are rejected`() {
        assertThat(parse(withStep("""{"op":"tap","text":"x","locators":[{"kind":"id","value":"a"}]}""")).rejected).hasSize(1)
        assertThat(parse(withStep("""{"op":"tap"}""")).rejected.single().reason).contains("tap needs locators")
        assertThat(parse(withStep("""{"op":"tap","locators":[{"kind":"xpath","value":"//a"}]}""")).rejected.single().reason).contains("unknown kind")
        assertThat(parse(withStep("""{"op":"tap","locators":[{"kind":"id","value":"com.app:id/a"}]}""")).rejected).hasSize(1)
        assertThat(parse(withStep("""{"op":"tap","locators":[{"kind":"bounds","bounds":[0.5,0.5,0.4,0.6]}]}""")).rejected).hasSize(1)
        assertThat(parse(withStep("""{"op":"type","text":"{nope}"}""")).rejected.single().reason).contains("unknown slot")
        assertThat(parse(withStep("""{"op":"type","text":"a\nb"}""")).rejected.single().reason).contains("one plain line")
        assertThat(parse(withStep("""{"op":"scroll","direction":"sideways"}""")).rejected).hasSize(1)
    }

    @Test
    fun `deep links to calls, payments, intents or compose links and slots in the host are rejected`() {
        for (uri in listOf("tel:{q}", "upi://pay?pa={q}", "intent://x#Intent;end", "https://wa.me/1?text={q}", "https://{q}.example.com/a")) {
            val r = parse(withStep("""{"op":"open_deeplink","uri":"$uri"}"""))
            assertThat(r.accepted).isEmpty()
        }
        assertThat(parse(withStep("""{"op":"open_deeplink","uri":"https://video.example.com/results?q={q}"}""")).accepted).hasSize(1)
    }

    @Test
    fun `id must match package and name, own package and duplicates rejected`() {
        val wrongId = bundleJson("""{"id":"com.example.other/x","package":"com.example.video","name":"x","description":"d","versionMin":1,"versionMax":2,"steps":[{"op":"back"}]}""")
        assertThat(parse(wrongId).rejected.single().reason).contains("id must be")
        val own = bundleJson("""{"id":"io.androllm.app/x","package":"io.androllm.app","name":"x","description":"d","versionMin":1,"versionMax":2,"steps":[{"op":"back"}]}""")
        assertThat(parse(own).rejected.single().reason).contains("assistant itself")
        val dup = bundleJson("$VALID_PLAYBOOK_JSON,$VALID_PLAYBOOK_JSON")
        assertThat(parse(dup).accepted).hasSize(1)
        assertThat(parse(dup).rejected.single().reason).isEqualTo("duplicate id")
    }

    @Test
    fun `risky targets upgrade the playbook to confirm`() {
        val p = PlaybookValidator.withComputedRisk(PlaybookFixtures.sendPlaybook())
        assertThat(p.risk).isEqualTo(Playbook.RISK_CONFIRM)
        assertThat(p.steps[1].risky).isTrue()
        assertThat(p.steps[0].risky).isFalse()
        for (label in listOf("Отправить", "Göndər", "Pay", "Delete", "Call", "Post", "Allow")) {
            val step = PlaybookStep(op = "tap", locators = listOf(Locator("text", label)))
            assertThat(PlaybookValidator.isRiskyStep(step)).isTrue()
        }
        assertThat(PlaybookValidator.isRiskyStep(PlaybookStep(op = "tap", locators = listOf(Locator("id", "search_button"))))).isFalse()
        assertThat(PlaybookValidator.withComputedRisk(PlaybookFixtures.searchPlaybook()).risk).isEqualTo(Playbook.RISK_NONE)
    }

    @Test
    fun `triggers must use known slots and contain a literal word`() {
        val bad = bundleJson("""{"id":"com.example.video/x","package":"com.example.video","name":"x","description":"d","versionMin":1,"versionMax":2,
            "params":[{"name":"q"}],"triggers":["{q}"],"steps":[{"op":"back"}]}""")
        assertThat(parse(bad).rejected.single().reason).contains("literal")
        val unknown = bad.replace("[\"{q}\"]", "[\"find {z}\"]")
        assertThat(parse(unknown).rejected.single().reason).contains("unknown slot")
    }
}
