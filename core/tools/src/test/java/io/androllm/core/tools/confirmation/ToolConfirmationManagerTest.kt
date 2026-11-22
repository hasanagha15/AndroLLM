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
        toolName = "send_sms",
        toolDisplayName = "SMS",
        actionSummary = "Run SMS — phone=+91…",
        speakableQuestion = "Do you want me to send the SMS? Say yes or no."
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
    fun `cancelPending denies every waiter`() = runTest {
        val manager = ToolConfirmationManager()
        val result = CompletableDeferred<Boolean>()
        val job = launch { result.complete(manager.awaitDecision(confirmation("c4"))) }
        while (manager.pending.value == null) kotlinx.coroutines.yield()
        manager.cancelPending()
        job.join()
        assertThat(result.await()).isFalse()
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
        for (tool in listOf("tesla_command", "whatsapp_reply", "glasses_show_text")) {
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
}
