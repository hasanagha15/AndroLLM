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

/** Н2 / gate Б1: tap-only cards in a voice turn — hint, hold, outcome (matched by decision seq). */
class VoiceCardWatcherTest {

    private fun card(id: String, tool: String, seq: Long, turn: String? = "turn-1", remote: String? = null) =
        PendingToolConfirmation(
            id = id,
            toolName = tool,
            toolDisplayName = tool.replace('_', ' '),
            actionSummary = "Run $tool",
            speakableQuestion = "Do you want me to $tool?",
            remoteOrigin = remote,
            voiceTurnId = turn,
            decisionSeq = seq
        )

    private fun outcome(c: PendingToolConfirmation, approved: Boolean) =
        ConfirmationOutcome(c.id, approved, c.decisionSeq)

    @Test
    fun `tap-only card of this turn is announced once and holds the turn`() {
        val w = VoiceCardWatcher("turn-1")
        val c = card("c1", "tesla_command", seq = 1)
        val first = w.update(c, emptyList(), 1_000)
        assertThat(first).containsExactly(VoiceCardWatcher.Event.TapOnlyCard(c))
        assertThat(w.holdsTurn(1_000)).isTrue()
        // Still pending 4 minutes later: no repeat, still holding (no first-token timeout).
        assertThat(w.update(c, emptyList(), 241_000)).isEmpty()
        assertThat(w.holdsTurn(241_000)).isTrue()
    }

    @Test
    fun `deny or timeout reports a cancelled outcome and releases the turn`() {
        val w = VoiceCardWatcher("turn-1")
        val c = card("c1", "whatsapp_reply", seq = 3)
        w.update(c, emptyList(), 0)
        val events = w.update(null, listOf(outcome(c, false)), 300_000)
        assertThat(events).containsExactly(VoiceCardWatcher.Event.Resolved(c, approved = false))
        assertThat(w.holdsTurn(300_000)).isFalse()
        // The same log entry is not reported twice.
        assertThat(w.update(null, listOf(outcome(c, false)), 301_000)).isEmpty()
    }

    @Test
    fun `stale outcome with the same call id never resolves the new card (gate repro)`() {
        // Reviewer's ReviewStaleOutcomeTest: previous "закрой машину" was denied
        // as call_abc_0; the repeated request reuses the deterministic id.
        val w = VoiceCardWatcher("turn-1")
        val stale = ConfirmationOutcome("call_abc_0", approved = false, seq = 7)
        val fresh = card("call_abc_0", "tesla_command", seq = 8)
        assertThat(w.update(fresh, listOf(stale), 0)).containsExactly(VoiceCardWatcher.Event.TapOnlyCard(fresh))
        assertThat(w.update(fresh, listOf(stale), 1_000)).isEmpty()
        assertThat(w.holdsTurn(1_000)).isTrue()
        // A stale APPROVAL must not start the post-approval grace either.
        val staleOk = ConfirmationOutcome("call_abc_0", approved = true, seq = 7)
        assertThat(w.update(fresh, listOf(staleOk), 2_000)).isEmpty()
        // Only the outcome of THIS decision resolves it.
        assertThat(w.update(null, listOf(stale, outcome(fresh, false)), 3_000))
            .containsExactly(VoiceCardWatcher.Event.Resolved(fresh, false))
    }

    @Test
    fun `two cards closing within one tick both resolve`() {
        val w = VoiceCardWatcher("turn-1")
        val a = card("a", "whatsapp_reply", seq = 10)
        val b = card("b", "whatsapp_reply", seq = 11)
        w.update(a, emptyList(), 0)
        w.update(b, emptyList(), 500) // b seen while a's outcome was not polled yet
        val events = w.update(null, listOf(outcome(a, true), outcome(b, false)), 1_000)
        assertThat(events).containsExactly(
            VoiceCardWatcher.Event.Resolved(a, true),
            VoiceCardWatcher.Event.Resolved(b, false)
        ).inOrder()
        assertThat(w.holdsTurn(1_000)).isFalse()
    }

    @Test
    fun `approval keeps holding while the approved tool runs until activity or grace`() {
        val w = VoiceCardWatcher("turn-1", postApprovalGraceMs = 90_000)
        val c = card("c1", "tesla_command", seq = 1)
        w.update(c, emptyList(), 0)
        val events = w.update(null, listOf(outcome(c, true)), 10_000)
        assertThat(events).containsExactly(VoiceCardWatcher.Event.Resolved(c, approved = true))
        assertThat(w.holdsTurn(70_000)).isTrue()
        assertThat(w.holdsTurn(100_001)).isFalse()
        val c2 = card("c2", "tesla_command", seq = 2)
        w.update(c2, emptyList(), 200_000)
        w.update(null, listOf(outcome(c2, true)), 201_000)
        w.onActivity()
        assertThat(w.holdsTurn(202_000)).isFalse()
    }

    @Test
    fun `cards of other turns or the typed chat are ignored`() {
        val w = VoiceCardWatcher("turn-1")
        assertThat(w.update(card("t", "tesla_command", 1, turn = null), emptyList(), 0)).isEmpty()
        assertThat(w.holdsTurn(0)).isFalse()
        assertThat(w.update(card("o", "tesla_command", 2, turn = "turn-2"), emptyList(), 0)).isEmpty()
        assertThat(w.holdsTurn(0)).isFalse()
    }

    @Test
    fun `a voice-answerable card holds the turn but is not announced`() {
        val w = VoiceCardWatcher("turn-1")
        val sms = card("s", "set_brightness", 1)
        assertThat(w.update(sms, emptyList(), 0)).isEmpty()
        assertThat(w.holdsTurn(0)).isTrue()
        assertThat(w.update(null, listOf(outcome(sms, true)), 5_000)).isEmpty()
        assertThat(w.holdsTurn(5_000)).isFalse()
    }

    @Test
    fun `whatsapp second card is announced after the first`() {
        val w = VoiceCardWatcher("turn-1")
        val a = card("a", "whatsapp_reply", 1)
        val b = card("b", "whatsapp_reply", 2)
        w.update(a, emptyList(), 0)
        val events = w.update(b, listOf(outcome(a, true)), 3_000)
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
                manager.awaitDecision(card("x", "tesla_command", seq = 0, turn = null))
            })
        }
        while (manager.pending.value == null) yield()
        val events = w.update(manager.pending.value, manager.recentOutcomes.value, 0)
        assertThat(events.single()).isInstanceOf(VoiceCardWatcher.Event.TapOnlyCard::class.java)
        assertThat(w.holdsTurn(0)).isTrue()
        manager.deny("x")
        job.join()
        assertThat(result.await()).isFalse()
        val after = w.update(manager.pending.value, manager.recentOutcomes.value, 1_000)
        assertThat(after.single()).isEqualTo(
            VoiceCardWatcher.Event.Resolved((events.single() as VoiceCardWatcher.Event.TapOnlyCard).card, false)
        )
        manager.setVoiceResponder(null)
    }

    @Test
    fun `end to end - two turns reusing the same call id, denied card never cancels the next one`() = runTest {
        val manager = ToolConfirmationManager()

        // Turn 1: "закрой машину" → call_abc_0 → Deny.
        val w1 = VoiceCardWatcher("turn-1")
        val job1 = launch {
            withContext(VoiceTurnOrigin("turn-1")) {
                manager.awaitDecision(card("call_abc_0", "tesla_command", seq = 0, turn = null))
            }
        }
        while (manager.pending.value == null) yield()
        assertThat(w1.update(manager.pending.value, manager.recentOutcomes.value, 0)).hasSize(1)
        manager.deny("call_abc_0")
        job1.join()
        assertThat(w1.update(manager.pending.value, manager.recentOutcomes.value, 1_000).single())
            .isInstanceOf(VoiceCardWatcher.Event.Resolved::class.java)

        // Turn 2: same request, same deterministic id, card live.
        val w2 = VoiceCardWatcher("turn-2")
        val result2 = CompletableDeferred<Boolean>()
        val job2 = launch {
            result2.complete(withContext(VoiceTurnOrigin("turn-2")) {
                manager.awaitDecision(card("call_abc_0", "tesla_command", seq = 0, turn = null))
            })
        }
        while (manager.pending.value == null) yield()
        val announced = w2.update(manager.pending.value, manager.recentOutcomes.value, 2_000)
        assertThat(announced.single()).isInstanceOf(VoiceCardWatcher.Event.TapOnlyCard::class.java)
        // Several ticks while the card waits: the old (denied) outcome with the
        // same id stays in the log but must not produce "Отменено".
        for (t in 3..6) {
            assertThat(w2.update(manager.pending.value, manager.recentOutcomes.value, t * 1_000L)).isEmpty()
            assertThat(w2.holdsTurn(t * 1_000L)).isTrue()
        }
        assertThat(manager.pending.value).isNotNull()
        manager.confirm("call_abc_0")
        job2.join()
        assertThat(result2.await()).isTrue()
        assertThat(w2.update(manager.pending.value, manager.recentOutcomes.value, 7_000).single())
            .isEqualTo(
                VoiceCardWatcher.Event.Resolved(
                    (announced.single() as VoiceCardWatcher.Event.TapOnlyCard).card, approved = true
                )
            )
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
