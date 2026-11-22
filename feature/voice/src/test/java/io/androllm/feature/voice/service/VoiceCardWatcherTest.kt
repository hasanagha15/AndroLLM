package io.androllm.feature.voice.service

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.confirmation.ConfirmationOutcome
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.origin.VoiceTurnOrigin
import io.androllm.core.voice.lang.VoiceLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Test

/** Н2: tap-only cards in a voice turn — hint, hold, outcome. */
class VoiceCardWatcherTest {

    private fun card(id: String, tool: String, turn: String? = "turn-1", remote: String? = null) =
        PendingToolConfirmation(
            id = id,
            toolName = tool,
            toolDisplayName = tool.replace('_', ' '),
            actionSummary = "Run $tool",
            speakableQuestion = "Do you want me to $tool?",
            remoteOrigin = remote,
            voiceTurnId = turn
        )

    @Test
    fun `tap-only card of this turn is announced once and holds the turn`() {
        val w = VoiceCardWatcher("turn-1")
        val c = card("c1", "tesla_command")
        val first = w.update(c, null, 1_000)
        assertThat(first).containsExactly(VoiceCardWatcher.Event.TapOnlyCard(c))
        assertThat(w.holdsTurn(1_000)).isTrue()
        // Still pending 4 minutes later: no repeat, still holding (no first-token timeout).
        assertThat(w.update(c, null, 241_000)).isEmpty()
        assertThat(w.holdsTurn(241_000)).isTrue()
    }

    @Test
    fun `deny or timeout reports a cancelled outcome and releases the turn`() {
        val w = VoiceCardWatcher("turn-1")
        val c = card("c1", "whatsapp_reply")
        w.update(c, null, 0)
        val events = w.update(null, ConfirmationOutcome("c1", approved = false), 300_000)
        assertThat(events).containsExactly(VoiceCardWatcher.Event.Resolved(c, approved = false))
        assertThat(w.holdsTurn(300_000)).isFalse()
        // The stale outcome is not reported twice.
        assertThat(w.update(null, ConfirmationOutcome("c1", approved = false), 301_000)).isEmpty()
    }

    @Test
    fun `approval keeps holding while the approved tool runs until activity or grace`() {
        val w = VoiceCardWatcher("turn-1", postApprovalGraceMs = 90_000)
        val c = card("c1", "tesla_command")
        w.update(c, null, 0)
        val events = w.update(null, ConfirmationOutcome("c1", approved = true), 10_000)
        assertThat(events).containsExactly(VoiceCardWatcher.Event.Resolved(c, approved = true))
        // Tesla bridge call takes 60 s with no status: still holding.
        assertThat(w.holdsTurn(70_000)).isTrue()
        assertThat(w.holdsTurn(100_001)).isFalse()
        // First token / tool status ends the grace immediately.
        w.update(card("c2", "tesla_command"), null, 200_000)
        w.update(null, ConfirmationOutcome("c2", approved = true), 201_000)
        w.onActivity()
        assertThat(w.holdsTurn(202_000)).isFalse()
    }

    @Test
    fun `cards of other turns or the typed chat are ignored`() {
        val w = VoiceCardWatcher("turn-1")
        assertThat(w.update(card("t", "tesla_command", turn = null), null, 0)).isEmpty()
        assertThat(w.holdsTurn(0)).isFalse()
        assertThat(w.update(card("o", "tesla_command", turn = "turn-2"), null, 0)).isEmpty()
        assertThat(w.holdsTurn(0)).isFalse()
    }

    @Test
    fun `a voice-answerable card holds the turn but is not announced`() {
        val w = VoiceCardWatcher("turn-1")
        val sms = card("s", "send_sms")
        assertThat(w.update(sms, null, 0)).isEmpty()
        assertThat(w.holdsTurn(0)).isTrue()
        assertThat(w.update(null, ConfirmationOutcome("s", true), 5_000)).isEmpty()
        assertThat(w.holdsTurn(5_000)).isFalse()
    }

    @Test
    fun `whatsapp second card is announced after the first`() {
        val w = VoiceCardWatcher("turn-1")
        val a = card("a", "whatsapp_reply")
        val b = card("b", "whatsapp_reply")
        w.update(a, null, 0)
        val events = w.update(b, ConfirmationOutcome("a", true), 3_000)
        assertThat(events).containsExactly(
            VoiceCardWatcher.Event.Resolved(a, true),
            VoiceCardWatcher.Event.TapOnlyCard(b)
        ).inOrder()
        assertThat(w.holdsTurn(3_000)).isTrue()
    }

    @Test
    fun `end to end with the real manager - card stamped by the voice turn is seen and resolved`() = runTest {
        val manager = ToolConfirmationManager()
        manager.setVoiceResponder { true } // a spoken "yes" must not approve a tap-only card
        val w = VoiceCardWatcher("turn-9")
        val result = CompletableDeferred<Boolean>()
        val job = launch {
            result.complete(withContext(VoiceTurnOrigin("turn-9")) {
                manager.awaitDecision(card("x", "tesla_command", turn = null))
            })
        }
        while (manager.pending.value == null) yield()
        val events = w.update(manager.pending.value, manager.lastOutcome.value, 0)
        assertThat(events.single()).isInstanceOf(VoiceCardWatcher.Event.TapOnlyCard::class.java)
        assertThat(w.holdsTurn(0)).isTrue()
        manager.deny("x")
        job.join()
        assertThat(result.await()).isFalse()
        val after = w.update(manager.pending.value, manager.lastOutcome.value, 1_000)
        assertThat(after.single()).isEqualTo(
            VoiceCardWatcher.Event.Resolved((events.single() as VoiceCardWatcher.Event.TapOnlyCard).card, false)
        )
        manager.setVoiceResponder(null)
    }

    @Test
    fun `phrases exist for ru en az and auto falls back to russian`() {
        assertThat(VoicePhrases.confirmInApp(VoiceLanguage.RU)).isEqualTo("Подтвердите это действие в приложении.")
        assertThat(VoicePhrases.confirmInApp(VoiceLanguage.EN)).contains("confirm this action in the app")
        assertThat(VoicePhrases.confirmInApp(VoiceLanguage.AZ)).contains("təsdiq")
        assertThat(VoicePhrases.cancelled(VoiceLanguage.RU)).isEqualTo("Отменено.")
        assertThat(VoicePhrases.cancelled(VoiceLanguage.AUTO)).isEqualTo("Отменено.")
        assertThat(VoicePhrases.busyRemote(VoiceLanguage.RU)).startsWith("Занято")
        assertThat(VoicePhrases.busyRemote(VoiceLanguage.EN)).contains("wait")
        assertThat(VoicePhrases.busyRemote(VoiceLanguage.AZ)).contains("Gözləyin")
    }
}
