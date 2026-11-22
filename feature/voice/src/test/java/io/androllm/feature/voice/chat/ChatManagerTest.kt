package io.androllm.feature.voice.chat

import com.google.common.truth.Truth.assertThat
import io.androllm.core.cloud.CloudGateway
import io.androllm.core.common.Result
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.MessageRepository
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.memory.MemoryManager
import io.androllm.core.models.Conversation
import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.coordinator.ToolRunCoordinator
import io.androllm.core.tools.origin.RemoteToolOrigin
import io.androllm.core.tools.prompt.ToolAdvertisementBudget
import io.androllm.core.tools.prompt.ToolPromptBuilder
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.models.BackendType
import io.androllm.engine.models.EngineModelInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Test

class ChatManagerTest {

    private val engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
    private val engineRepository = mockk<EngineRepository>(relaxed = true).also {
        every { it.engineState } returns engineState
    }
    private val inferenceEngine = mockk<InferenceEngine>(relaxed = true).also {
        coEvery { it.isLoaded() } returns false
    }
    private val cloudGateway = mockk<CloudGateway>(relaxed = true).also {
        coEvery { it.isConfigured() } returns false
    }
    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    private val messageRepository = mockk<MessageRepository>(relaxed = true).also {
        coEvery { it.upsert(any()) } returns Result.Success(Unit)
        every { it.observeByConversationId(any()) } returns flowOf(emptyList())
    }
    private val memoryManager = mockk<MemoryManager>(relaxed = true)
    private val toolPromptBuilder = mockk<ToolPromptBuilder>(relaxed = true)

    private fun manager() = ChatManager(
        engineRepository = engineRepository,
        inferenceEngine = inferenceEngine,
        cloudGateway = cloudGateway,
        conversationRepository = conversationRepository,
        messageRepository = messageRepository,
        memoryManager = memoryManager,
        settingsRepository = mockk<SettingsRepository>(relaxed = true),
        toolCoordinator = mockk<ToolRunCoordinator>(relaxed = true),
        automationSettingsStore = mockk<AutomationSettingsStore>(relaxed = true),
        traceStore = mockk<ToolExecutionTraceStore>(relaxed = true),
        variableStore = mockk<AgentVariableStore>(relaxed = true),
        toolPromptBuilder = toolPromptBuilder
    )

    private fun model(ctx: Int, cap: Int = Int.MAX_VALUE) = EngineModelInfo(
        id = "m",
        filePath = "",
        contextLength = ctx,
        vocabSize = 0,
        backend = BackendType.values().first(),
        toolAdvertisementCapChars = cap
    )

    // ── Н3 ──

    @Test
    fun `voice turn gets the busy reply while a remote task holds the pipeline`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { conversationRepository.existsById("remote-conv") } coAnswers {
            gate.await()
            Result.Success(true)
        }
        val chat = manager()
        val remote = launch {
            withContext(RemoteToolOrigin()) {
                chat.sendMessageStream("remote task", conversationId = "remote-conv", useMemory = false).toList()
            }
        }
        while (!chat.isRemoteTurnActive()) yield()

        val voice = chat.sendMessageStream("привет", busyReply = "BUSY").toList()

        assertThat(voice).containsExactly("BUSY")
        remote.cancel()
        remote.join()
        assertThat(chat.isRemoteTurnActive()).isFalse()
    }

    @Test
    fun `without a remote task the busy reply is not used`() = runTest {
        coEvery { conversationRepository.observeActive() } returns flowOf(listOf(Conversation("c1", "t", 0, 0)))
        val out = manager().sendMessageStream("привет", lowLatencyMode = true, busyReply = "BUSY").toList()
        assertThat(out).doesNotContain("BUSY")
        assertThat(out).isNotEmpty()
    }

    // ── Н4 ──

    private suspend fun advertisedBudgetFor(state: EngineState, cloud: Boolean = false): Pair<Int, String> {
        engineState.value = state
        coEvery { cloudGateway.isConfigured() } returns cloud
        coEvery { conversationRepository.observeActive() } returns flowOf(listOf(Conversation("c1", "t", 0, 0)))
        val maxChars = slot<Int>()
        val query = slot<String>()
        coEvery { toolPromptBuilder.advertisement(capture(maxChars), capture(query), any()) } returns null
        manager().sendMessageStream("закрой машину", lowLatencyMode = true).toList()
        return maxChars.captured to query.captured
    }

    @Test
    fun `voice advertisement is budgeted to a 4096-token local model and routed by the request`() = runTest {
        val (budget, query) = advertisedBudgetFor(EngineState.Ready(model(4096)))
        assertThat(budget).isEqualTo(ToolAdvertisementBudget.charsFor(4096))
        assertThat(budget).isAtMost(4096)
        assertThat(query).isEqualTo("закрой машину")
    }

    @Test
    fun `family cap and 2048 context shrink the budget further`() = runTest {
        assertThat(advertisedBudgetFor(EngineState.Ready(model(8192, cap = 4500))).first).isEqualTo(4500)
        assertThat(advertisedBudgetFor(EngineState.Ready(model(2048))).first).isAtMost(2048)
    }

    @Test
    fun `cloud turns stay unbounded`() = runTest {
        assertThat(advertisedBudgetFor(EngineState.Ready(model(4096)), cloud = true).first).isEqualTo(0)
    }
}
