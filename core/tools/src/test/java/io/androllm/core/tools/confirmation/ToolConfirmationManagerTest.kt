package io.androllm.core.tools.confirmation

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class ToolConfirmationManagerTest {

    private fun confirmation(id: String) = PendingToolConfirmation(
        id = id,
        // Branch U: a voice-answerable tool (outward-communication tools are tap-only).
        toolName = "set_brightness",
        toolDisplayName = "System",
        actionSummary = "Run System — percent=40",
        speakableQuestion = "Do you want me to set the brightness? Say yes or no."
    )

    @Test
    fun `awaitDecision returns true after confirm`() = runTest {
        val manager = ToolConfirmationManager()
        val c = confirmation("c1")
        val result = CompletableDeferred<Boolean>()

        val job = launch {
            result.complete(manager.awaitDecision(c))
        }
        // Publish then approve from the UI thread.
        while (manager.pending.value == null) {
            kotlinx.coroutines.yield()
        }
        manager.confirm("c1")
        job.join()

        assertThat(result.await()).isTrue()
        assertThat(manager.pending.value).isNull()
    }

    @Test
    fun `awaitDecision returns false after deny`() = runTest {
        val manager = ToolConfirmationManager()
        val c = confirmation("c2")
        val result = CompletableDeferred<Boolean>()
        val job = launch { result.complete(manager.awaitDecision(c)) }
        while (manager.pending.value == null) kotlinx.coroutines.yield()
        manager.deny("c2")
        job.join()
        assertThat(result.await()).isFalse()
    }

    // The voice responder runs on Dispatchers.Default (real thread), so these
    // use runBlocking (real time) instead of runTest (virtual time) — a 300s
    // virtual timeout would otherwise fire before the real thread completes.

    @Test
    fun `voice responder approves when it says yes`() = runBlocking {
        val manager = ToolConfirmationManager()
        manager.setVoiceResponder { true }
        assertThat(manager.awaitDecision(confirmation("c3"))).isTrue()
        manager.setVoiceResponder(null)
    }

    @Test
    fun `chat card approves even when the voice responder abstains`() = runBlocking {
        val manager = ToolConfirmationManager()
        // Muted / no-TTS voice surface: it abstains and the card must decide.
        manager.setVoiceResponder { null }
        val result = CompletableDeferred<Boolean>()
        val job = launch { result.complete(manager.awaitDecision(confirmation("c5"))) }
        while (manager.pending.value == null) kotlinx.coroutines.yield()
        manager.confirm("c5")
        job.join()
        assertThat(result.await()).isTrue()
        assertThat(manager.pending.value).isNull()
        manager.setVoiceResponder(null)
    }

    @Test
    fun `chat card deny wins over a still-listening voice responder`() = runBlocking {
        val manager = ToolConfirmationManager()
        // Simulates the mic listening forever: the card must still decide and
        // the voice listener gets cancelled.
        manager.setVoiceResponder { kotlinx.coroutines.awaitCancellation() }
        val result = CompletableDeferred<Boolean>()
        val job = launch { result.complete(manager.awaitDecision(confirmation("c6"))) }
        while (manager.pending.value == null) kotlinx.coroutines.yield()
        manager.deny("c6")
        job.join()
        assertThat(result.await()).isFalse()
        assertThat(manager.pending.value).isNull()
        manager.setVoiceResponder(null)
    }

    @Test
    fun `cancelPending denies a typed-chat waiter`() = runTest {
        val manager = ToolConfirmationManager()
        val result = CompletableDeferred<Boolean>()
        val job = launch { result.complete(manager.awaitDecision(confirmation("c4"))) }
        while (manager.pending.value == null) kotlinx.coroutines.yield()
        manager.cancelPending()
        job.join()
        assertThat(result.await()).isFalse()
        assertThat(manager.pending.value).isNull()
        assertThat(manager.lastOutcome.value?.let { it.id to it.approved }).isEqualTo("c4" to false)
    }

    @Test
    fun `typed-chat cancelPending never denies a remote card`() = runTest {
        val manager = ToolConfirmationManager()
        val result = CompletableDeferred<Boolean>()
        val job = launch {
            result.complete(
                kotlinx.coroutines.withContext(io.androllm.core.tools.origin.RemoteToolOrigin()) {
                    manager.awaitDecision(confirmation("r1").copy(toolName = "tesla_command"))
                }
            )
        }
        while (manager.pending.value == null) kotlinx.coroutines.yield()
        assertThat(manager.pending.value!!.surface).isEqualTo(ConfirmationSurface.REMOTE)
        manager.cancelPending() // Stop / conversation switch in typed chat
        manager.cancelPending(ConfirmationSurface.VOICE) // voice service stop
        kotlinx.coroutines.yield()
        assertThat(result.isCompleted).isFalse()
        assertThat(manager.pending.value?.id).isEqualTo("r1")
        manager.confirm("r1")
        job.join()
        assertThat(result.await()).isTrue()
        assertThat(manager.lastOutcome.value?.let { it.id to it.approved }).isEqualTo("r1" to true)
    }

    @Test
    fun `cancelAllPending denies a remote card`() = runTest {
        val manager = ToolConfirmationManager()
        val result = CompletableDeferred<Boolean>()
        val job = launch {
            result.complete(
                kotlinx.coroutines.withContext(io.androllm.core.tools.origin.RemoteToolOrigin()) {
                    manager.awaitDecision(confirmation("r2"))
                }
            )
        }
        while (manager.pending.value == null) kotlinx.coroutines.yield()
        manager.cancelAllPending()
        job.join()
        assertThat(result.await()).isFalse()
    }

    @Test
    fun `voice turn cards are stamped and cancelled only by the voice scope`() = runTest {
        val manager = ToolConfirmationManager()
        val result = CompletableDeferred<Boolean>()
        val job = launch {
            result.complete(
                kotlinx.coroutines.withContext(io.androllm.core.tools.origin.VoiceTurnOrigin("turn-7")) {
                    manager.awaitDecision(confirmation("v1").copy(toolName = "tesla_command"))
                }
            )
        }
        while (manager.pending.value == null) kotlinx.coroutines.yield()
        val card = manager.pending.value!!
        assertThat(card.voiceTurnId).isEqualTo("turn-7")
        assertThat(card.surface).isEqualTo(ConfirmationSurface.VOICE)
        manager.cancelPending() // typed scope: untouched
        kotlinx.coroutines.yield()
        assertThat(result.isCompleted).isFalse()
        manager.cancelPending(ConfirmationSurface.VOICE)
        job.join()
        assertThat(result.await()).isFalse()
    }

    @Test
    fun `remote origin wins over a voice turn marker`() = runTest {
        val manager = ToolConfirmationManager()
        val job = launch {
            kotlinx.coroutines.withContext(
                io.androllm.core.tools.origin.VoiceTurnOrigin("t") + io.androllm.core.tools.origin.RemoteToolOrigin()
            ) { manager.awaitDecision(confirmation("x1")) }
        }
        while (manager.pending.value == null) kotlinx.coroutines.yield()
        assertThat(manager.pending.value!!.surface).isEqualTo(ConfirmationSurface.REMOTE)
        assertThat(manager.pending.value!!.voiceTurnId).isNull()
        manager.deny("x1")
        job.join()
    }

    @Test
    fun `confirmation prompt substitutes argument placeholders`() {
        val call = io.androllm.core.tools.api.ToolCall(
            id = "x",
            name = "send_sms",
            arguments = JsonObject(mapOf("phone" to kotlinx.serialization.json.JsonPrimitive("+919876543210")))
        )
        val spec = io.androllm.core.tools.api.ToolSpec(
            name = "send_sms",
            description = "send",
            confirmationPrompt = "send the SMS to {phone}"
        )
        val conf = buildConfirmation(call, spec)
        assertThat(conf.speakableQuestion).contains("+919876543210")
    }

    @Test
    fun `car messaging and glasses tools are tap-only and ignore a spoken yes`() = runBlocking {
        for (tool in listOf(
            "tesla_command", "whatsapp_reply", "glasses_show_text",
            "send_sms", "make_call", "send_email", "note_delete", "share_text"
        )) {
            val manager = ToolConfirmationManager()
            var asked = false
            manager.setVoiceResponder { asked = true; true }
            val c = confirmation("tap-$tool").copy(toolName = tool)
            val result = CompletableDeferred<Boolean>()
            val job = launch { result.complete(manager.awaitDecision(c)) }
            while (manager.pending.value == null) kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(200)
            assertThat(result.isCompleted).isFalse()
            manager.deny("tap-$tool")
            job.join()
            assertThat(result.await()).isFalse()
            assertThat(asked).isFalse()
            manager.setVoiceResponder(null)
        }
    }

    @Test
    fun `isTapOnly covers remote origin and listed tools only`() {
        assertThat(ToolConfirmationManager.isTapOnly(confirmation("a"))).isFalse()
        assertThat(ToolConfirmationManager.isTapOnly(confirmation("b").copy(toolName = "tesla_command"))).isTrue()
        assertThat(ToolConfirmationManager.isTapOnly(confirmation("c").copy(remoteOrigin = "Jarvis"))).isTrue()
        for (n in listOf("Tesla_Command", "TESLA_COMMAND", " tesla_command ", "Glasses_Show_Text", "WhatsApp_Reply")) {
            assertThat(ToolConfirmationManager.isTapOnly(confirmation("n").copy(toolName = n))).isTrue()
        }
    }

    @Test
    fun `step 5 - sms and calls can never be approved by voice`() {
        for (n in listOf("send_sms", "make_call", "Send_Email", "note_delete", "share_text")) {
            assertThat(ToolConfirmationManager.isTapOnly(confirmation("n").copy(toolName = n))).isTrue()
        }
    }

    @Test
    fun `future outward tools are tap-only by name, read and device tools are not`() {
        for (n in listOf("mcp.slack_post_message", "telegram_send", "gmail_forward", "files_delete", "bank_transfer", "dial_number", "instagram_reply")) {
            assertThat(ToolConfirmationManager.isTapOnlyToolName(n)).isTrue()
        }
        for (n in listOf("calculate", "get_location", "set_brightness", "record_voice", "find_contacts", "read_notifications", "calendar")) {
            assertThat(ToolConfirmationManager.isTapOnlyToolName(n)).isFalse()
        }
    }

    @Test
    fun `the same call id gets a new decision sequence each time and both outcomes are logged`() = runTest {
        val manager = ToolConfirmationManager()
        val seqs = mutableListOf<Long>()
        repeat(2) { round ->
            val job = launch { manager.awaitDecision(confirmation("call_abc_0").copy(toolName = "tesla_command")) }
            while (manager.pending.value == null) kotlinx.coroutines.yield()
            seqs += manager.pending.value!!.decisionSeq
            if (round == 0) manager.deny("call_abc_0") else manager.confirm("call_abc_0")
            job.join()
        }
        assertThat(seqs[0]).isGreaterThan(0L)
        assertThat(seqs[1]).isGreaterThan(seqs[0])
        val log = manager.recentOutcomes.value
        assertThat(log.map { Triple(it.id, it.approved, it.seq) }).containsExactly(
            Triple("call_abc_0", false, seqs[0]),
            Triple("call_abc_0", true, seqs[1])
        ).inOrder()
    }

    @Test
    fun `outcome log is bounded`() = runTest {
        val manager = ToolConfirmationManager()
        repeat(ToolConfirmationManager.OUTCOME_LOG_SIZE + 5) { i ->
            val job = launch { manager.awaitDecision(confirmation("c$i")) }
            while (manager.pending.value == null) kotlinx.coroutines.yield()
            manager.deny("c$i")
            job.join()
        }
        assertThat(manager.recentOutcomes.value).hasSize(ToolConfirmationManager.OUTCOME_LOG_SIZE)
        assertThat(manager.recentOutcomes.value.last().id).isEqualTo("c${ToolConfirmationManager.OUTCOME_LOG_SIZE + 4}")
    }

    @Test
    fun `cancelIds denies only the listed cards - tool loop timeout of one turn`() = runTest {
        val manager = ToolConfirmationManager()
        val result = CompletableDeferred<Boolean>()
        val job = launch { result.complete(manager.awaitDecision(confirmation("turn-card"))) }
        while (manager.pending.value == null) kotlinx.coroutines.yield()
        assertThat(manager.cancelIds(listOf("other-turn"))).isEqualTo(0)
        kotlinx.coroutines.yield()
        assertThat(result.isCompleted).isFalse()
        assertThat(manager.cancelIds(listOf("turn-card", "other-turn"))).isEqualTo(1)
        job.join()
        assertThat(result.await()).isFalse()
        assertThat(manager.pending.value).isNull()
    }
}
