package io.androllm.feature.voice.chat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.androllm.core.cloud.CloudGateway
import io.androllm.core.common.Result
import io.androllm.core.common.log.ContentLog
import io.androllm.core.database.repository.ConversationRepository
import io.androllm.core.database.repository.MessageRepository
import io.androllm.core.database.repository.SettingsRepository
import io.androllm.core.memory.MemoryManager
import io.androllm.core.models.Conversation
import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.coordinator.ToolRunCoordinator
import io.androllm.core.tools.executor.ToolExecutor
import io.androllm.core.tools.planner.ToolPlanner
import io.androllm.core.tools.prompt.ToolPromptBuilder
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettings
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.models.StreamChunk
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.Collections

/**
 * Branch Q: a simulated voice turn (STT transcript -> tool planning -> tool
 * execution -> model reply) must not put the owner's words, tool arguments,
 * tool output or the reply into logs. Every Timber line written during the
 * turn is captured and checked for the planted markers.
 */
class VoiceTurnContentFreeLogsTest {

    private val captured = Collections.synchronizedList(mutableListOf<String>())
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            captured += "${tag.orEmpty()} $message"
        }
    }

    @Before
    fun plant() {
        ContentLog.enabled = false
        Timber.plant(tree)
    }

    @After
    fun uproot() {
        Timber.uproot(tree)
        ContentLog.enabled = false
    }

    private val engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
    private val engineRepository = mockk<EngineRepository>(relaxed = true).also {
        every { it.engineState } returns engineState
    }
    private val inferenceEngine = mockk<InferenceEngine>(relaxed = true)
    private val cloudGateway = mockk<CloudGateway>(relaxed = true).also {
        coEvery { it.isConfigured() } returns false
    }
    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    private val messageRepository = mockk<MessageRepository>(relaxed = true).also {
        coEvery { it.upsert(any()) } returns Result.Success(Unit)
        every { it.observeByConversationId(any()) } returns flowOf(emptyList())
    }
    private val personalContextStore = mockk<io.androllm.core.memory.personal.PersonalContextStore>(relaxed = true).also {
        coEvery { it.current() } returns io.androllm.core.memory.personal.PersonalContextSettings()
    }

    private suspend fun runVoiceTurn() {
        val registry = ToolRegistry().apply {
            register(object : Tool {
                override val spec = ToolSpec(
                    name = "read_notifications", description = "read", category = ToolCategory.INFORMATION
                )
                override suspend fun execute(arguments: JsonObject): ToolResult =
                    ToolResult.Success("$TOOL_OUT: SMS from $CONTACT says hi")
            })
        }
        val settingsStore = mockk<AutomationSettingsStore>()
        coEvery { settingsStore.current() } returns AutomationSettings()
        val executor = ToolExecutor(registry, settingsStore, ToolConfirmationManager(), mockk(relaxed = true))
        val planner = mockk<ToolPlanner>(relaxed = true)
        coEvery { planner.allowedTools() } returns registry.all().map { it.spec }
        var round = 0
        coEvery { planner.planLocal(any(), any()) } answers {
            round++
            if (round == 1) {
                listOf(ToolCall("c1", "read_notifications", buildJsonObject { put("filter", TOOL_ARG) }))
            } else emptyList()
        }
        val coordinator = ToolRunCoordinator(planner, executor, settingsStore, mockk(relaxed = true), registry)
        coEvery { inferenceEngine.isLoaded() } returns true
        every { inferenceEngine.generateChatStream(any(), any(), any()) } returns
            flowOf(Result.Success(StreamChunk("$REPLY ", false)), Result.Success(StreamChunk("", true)))
        coEvery { conversationRepository.observeActive() } returns flowOf(listOf(Conversation("c1", "t", 0, 0)))

        val chat = ChatManager(
            engineRepository = engineRepository,
            inferenceEngine = inferenceEngine,
            cloudGateway = cloudGateway,
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            memoryManager = mockk<MemoryManager>(relaxed = true),
            settingsRepository = mockk<SettingsRepository>(relaxed = true),
            toolCoordinator = coordinator,
            automationSettingsStore = settingsStore,
            traceStore = mockk<ToolExecutionTraceStore>(relaxed = true),
            variableStore = mockk<AgentVariableStore>(relaxed = true),
            toolPromptBuilder = mockk<ToolPromptBuilder>(relaxed = true),
            personalContextStore = personalContextStore
        )
        chat.sendMessageStream("  $USER_TEXT check my messages", lowLatencyMode = true).toList()
        assertWithMessage("the tool must have run").that(round).isAtLeast(1)
    }

    @Test
    fun `voice turn logs carry no user text, tool data or reply`() = runTest {
        runVoiceTurn()

        val logs = captured.toList()
        assertWithMessage("the turn must have logged something").that(logs).isNotEmpty()
        for (marker in listOf(USER_TEXT, TOOL_ARG, TOOL_OUT, CONTACT, REPLY)) {
            assertWithMessage("'$marker' leaked into logs:\n${logs.filter { it.contains(marker) }.joinToString("\n")}")
                .that(logs.none { it.contains(marker) }).isTrue()
        }
    }

    @Test
    fun `explicit debug content logging lets the text through`() = runTest {
        ContentLog.enabled = true
        runVoiceTurn()
        assertThat(captured.any { it.contains(USER_TEXT) }).isTrue()
    }

    private companion object {
        const val USER_TEXT = "Q7USERSECRET"
        const val TOOL_ARG = "Q7TOOLARG"
        const val TOOL_OUT = "Q7TOOLOUT"
        const val CONTACT = "Q7ContactName"
        const val REPLY = "Q7REPLYTEXT"
    }
}
