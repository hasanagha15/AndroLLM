package io.androllm.feature.voice.chat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
import io.androllm.core.models.Message
import io.androllm.core.models.MessageRole
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.confirmation.ConfirmationSurface
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.executor.ToolExecutor
import io.androllm.core.tools.origin.VoiceTurnOrigin
import io.androllm.core.tools.planner.ToolPlanner
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
    private val personalContextStore = mockk<io.androllm.core.memory.personal.PersonalContextStore>(relaxed = true).also {
        coEvery { it.current() } returns io.androllm.core.memory.personal.PersonalContextSettings()
    }

    private fun manager(
        toolCoordinator: ToolRunCoordinator = mockk(relaxed = true),
        automationSettingsStore: AutomationSettingsStore = mockk(relaxed = true)
    ) = ChatManager(
        engineRepository = engineRepository,
        inferenceEngine = inferenceEngine,
        cloudGateway = cloudGateway,
        conversationRepository = conversationRepository,
        messageRepository = messageRepository,
        memoryManager = memoryManager,
        settingsRepository = mockk<SettingsRepository>(relaxed = true),
        toolCoordinator = toolCoordinator,
        automationSettingsStore = automationSettingsStore,
        traceStore = mockk<ToolExecutionTraceStore>(relaxed = true),
        variableStore = mockk<AgentVariableStore>(relaxed = true),
        toolPromptBuilder = toolPromptBuilder,
        personalContextStore = personalContextStore
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

    @Test
    fun `busy voice turn still saves the request and the busy reply`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { conversationRepository.existsById("remote-conv") } coAnswers {
            gate.await()
            Result.Success(true)
        }
        coEvery { conversationRepository.observeActive() } returns flowOf(listOf(Conversation("owner-chat", "t", 0, 0)))
        val saved = mutableListOf<Message>()
        coEvery { messageRepository.upsert(capture(saved)) } returns Result.Success(Unit)
        val chat = manager()
        val remote = launch {
            withContext(RemoteToolOrigin()) {
                chat.sendMessageStream("remote task", conversationId = "remote-conv", useMemory = false).toList()
            }
        }
        while (!chat.isRemoteTurnActive()) yield()

        assertThat(chat.sendMessageStream("закрой машину", busyReply = "BUSY").toList()).containsExactly("BUSY")

        assertThat(saved.map { Triple(it.conversationId, it.role, it.content) }).containsExactly(
            Triple("owner-chat", MessageRole.USER, "закрой машину"),
            Triple("owner-chat", MessageRole.ASSISTANT, "BUSY")
        ).inOrder()
        remote.cancel()
    }

    // VoiceTurnOrigin travels ChatManager -> ToolRunCoordinator (async batch) -> ToolExecutor -> card

    private fun readTool(name: String, seen: MutableList<String?>): Tool = object : Tool {
        override val spec = ToolSpec(
            name = name, description = "read", category = ToolCategory.INFORMATION, cacheable = true
        )
        override suspend fun execute(arguments: JsonObject): ToolResult {
            seen += "$name@" + currentCoroutineContext()[VoiceTurnOrigin]?.turnId
            return ToolResult.Success("ok $name")
        }
    }

    @Test
    fun `voice turn marker reaches tools in a parallel batch and stamps the confirmation card`() = runTest {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String?>())
        var cardTurn: String? = "unset"
        var cardSurface: ConfirmationSurface? = null
        val confirmations = ToolConfirmationManager()
        val registry = ToolRegistry().apply {
            register(readTool("get_weather", seen))
            register(readTool("get_news", seen))
            register(object : Tool {
                override val spec = ToolSpec(
                    name = "tesla_command", description = "car", requiresConfirmation = true,
                    category = ToolCategory.DEVICE
                )
                override suspend fun execute(arguments: JsonObject): ToolResult {
                    seen += "tesla_command@" + currentCoroutineContext()[VoiceTurnOrigin]?.turnId
                    return ToolResult.Success("done")
                }
            })
        }
        val settingsStore = mockk<AutomationSettingsStore>()
        coEvery { settingsStore.current() } returns AutomationSettings()
        val executor = ToolExecutor(registry, settingsStore, confirmations, mockk(relaxed = true))
        val planner = mockk<ToolPlanner>(relaxed = true)
        coEvery { planner.allowedTools() } returns registry.all().map { it.spec }
        var round = 0
        var turn = 0
        coEvery { planner.planLocal(any(), any()) } answers {
            round++
            when {
                // Turn 1: a parallel read batch.
                turn == 1 && round == 1 -> listOf(
                    ToolCall("w1", "get_weather", buildJsonObject { }),
                    ToolCall("n1", "get_news", buildJsonObject { })
                )
                // Turn 2: a confirmed side effect.
                turn == 2 && round == 1 -> listOf(ToolCall("call_abc_0", "tesla_command", buildJsonObject { }))
                else -> emptyList()
            }
        }
        val coordinator = ToolRunCoordinator(planner, executor, settingsStore, mockk(relaxed = true), registry)
        coEvery { inferenceEngine.isLoaded() } returns true
        every { inferenceEngine.generateChatStream(any(), any(), any()) } returns emptyFlow()
        coEvery { conversationRepository.observeActive() } returns flowOf(listOf(Conversation("c1", "t", 0, 0)))

        val chat = manager(coordinator, settingsStore)
        turn = 1; round = 0
        withContext(VoiceTurnOrigin("turn-42")) {
            chat.sendMessageStream("weather and news", lowLatencyMode = true).toList()
        }
        turn = 2; round = 0
        val approver = launch {
            var spins = 0
            while (confirmations.pending.value == null && spins++ < 20_000) yield()
            if (confirmations.pending.value == null) return@launch
            cardTurn = confirmations.pending.value!!.voiceTurnId
            cardSurface = confirmations.pending.value!!.surface
            confirmations.confirm(confirmations.pending.value!!.id)
        }
        withContext(VoiceTurnOrigin("turn-42")) {
            chat.sendMessageStream("lock the car", lowLatencyMode = true).toList()
        }
        approver.join()

        // Every execution (parallel read batch and the confirmed call) ran inside the voice turn.
        assertWithMessage("rounds=$round").that(seen.toSet())
            .containsExactly("get_weather@turn-42", "get_news@turn-42", "tesla_command@turn-42")
        assertThat(cardTurn).isEqualTo("turn-42")
        assertThat(cardSurface).isEqualTo(ConfirmationSurface.VOICE)
    }
}
