package io.androllm.engine

import io.androllm.core.common.Result
import io.androllm.core.common.isError
import io.androllm.core.common.isSuccess
import io.androllm.core.models.Model
import io.androllm.engine.api.DefaultEngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.backend.BackendCapabilities
import io.androllm.engine.models.BackendType
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineCapabilities
import io.androllm.engine.models.EngineModelInfo
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.ModelLoadConfig
import io.androllm.engine.models.StreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch P:
 *  - issue 6: a memory-pressure unload is followed by a transparent reload
 *    on the next request (no "Model not loaded");
 *  - issue 3: the backend benchmark reports prefill and decode separately and
 *    restores the backend the USER selected (it used to restore CPU).
 */
class RepositoryReloadAndBenchmarkTest {

    private class RecordingEngine : InferenceEngine {
        val loadCalls = mutableListOf<ModelLoadConfig>()
        private var loaded: EngineModelInfo? = null

        override val capabilities = EngineCapabilities(name = "Rec", version = "1", backend = BackendType.CPU)
        private val _backendCapabilities = MutableStateFlow<BackendCapabilities>(BackendCapabilities.UNKNOWN)
        override val backendCapabilities: StateFlow<BackendCapabilities> = _backendCapabilities.asStateFlow()
        private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
        override val engineState: Flow<EngineState> = _engineState.asStateFlow()
        private val _stats = MutableStateFlow<EngineStats?>(null)
        override val stats: Flow<EngineStats?> = _stats.asStateFlow()

        override suspend fun initialize(config: io.androllm.engine.models.EngineConfig): Result<Unit> = Result.Success(Unit)
        override fun isLoaded(): Boolean = loaded != null
        override fun getLoadedModel(): EngineModelInfo? = loaded

        override suspend fun loadModel(model: Model, config: ModelLoadConfig): Result<EngineModelInfo> {
            loadCalls += config
            val backend = config.backend?.takeIf { it != BackendType.AUTO }
                ?: if (config.gpuLayers == 0) BackendType.CPU else BackendType.GPU
            val info = EngineModelInfo(model.id, model.filePath ?: "", 4096, 0, backend, generalName = model.name)
            loaded = info
            _engineState.value = EngineState.Ready(info)
            return Result.Success(info)
        }

        override suspend fun unloadModel(): Result<Unit> {
            loaded = null
            _engineState.value = EngineState.Unloaded
            return Result.Success(Unit)
        }

        override fun tokenStream(prompt: String, config: GenerationConfig): Flow<Result<StreamChunk>> = flow {
            _stats.value = EngineStats(
                promptTokens = 14, generatedTokens = 2, promptTokensPerSecond = 140f,
                decodeTokensPerSecond = 30f, firstTokenMs = 120, stopReason = "eos"
            )
            emit(Result.Success(StreamChunk("hello ", false)))
            emit(Result.Success(StreamChunk("world", false)))
            emit(Result.Success(StreamChunk("", true, generatedTokens = 2)))
        }

        override suspend fun buildChatPrompt(messages: List<ChatPromptMessage>, addAssistant: Boolean): Result<String> =
            Result.Success("")

        override suspend fun generate(prompt: String, config: GenerationConfig): Result<String> = Result.Success("hello world")

        override fun generateChatStream(
            messages: List<ChatPromptMessage>,
            addAssistant: Boolean,
            config: GenerationConfig
        ): Flow<Result<StreamChunk>> = tokenStream("", config)

        override fun cancel(): Result<Unit> = Result.Success(Unit)
        override fun benchmark(iterations: Int): Flow<Result<io.androllm.engine.models.BenchmarkResult>> = flow { }
        override suspend fun resetChat(): Result<Unit> = Result.Success(Unit)
        override suspend fun getDebugInfo(): Result<io.androllm.engine.models.EngineDebugInfo?> = Result.Success(null)
        override fun release() { loaded = null }
    }

    private val model = Model(id = "qwen", name = "Qwen3 0.6B", filePath = "/tmp/q.litertlm")
    private val chat = listOf(ChatPromptMessage(role = "user", content = "Hi"))

    @Test
    fun `memory pressure unload reloads transparently on the next chat turn`() = runTest {
        val engine = RecordingEngine()
        val repo = DefaultEngineRepository(engine)
        assertTrue(repo.loadModel(model, ModelLoadConfig(backend = BackendType.GPU, runSelfTest = false)).isSuccess())

        repo.unloadForMemoryPressure()
        assertTrue(!engine.isLoaded())

        val result = repo.generateChat(chat)
        assertTrue("turn after memory-pressure unload must succeed: $result", result.isSuccess())
        assertTrue(repo.generationState.value is GenerationState.Completed)
        assertEquals(BackendType.GPU, engine.loadCalls.last().backend)
        assertTrue(engine.isLoaded())
    }

    @Test
    fun `a manual unload still reports model not loaded`() = runTest {
        val engine = RecordingEngine()
        val repo = DefaultEngineRepository(engine)
        repo.loadModel(model, ModelLoadConfig(backend = BackendType.GPU, runSelfTest = false))
        repo.unloadModel()

        val result = repo.generateChat(chat)
        assertTrue(result.isError())
        assertEquals(1, engine.loadCalls.size)
    }

    @Test
    fun `benchmark reports prefill and decode separately and restores the user backend`() = runTest {
        val engine = RecordingEngine()
        val repo = DefaultEngineRepository(engine)
        val userConfig = ModelLoadConfig(backend = BackendType.GPU, runSelfTest = false)
        repo.loadModel(model, userConfig)

        val results = (repo.benchmarkBackends() as Result.Success).data
        assertTrue(results.isNotEmpty())
        results.forEach {
            assertEquals(140f, it.prefillTokensPerSecond)
            assertEquals(30f, it.decodeTokensPerSecond)
            assertEquals(120L, it.firstTokenMs)
        }
        assertEquals("restore must reload the user's own config", userConfig, engine.loadCalls.last())
        assertEquals(BackendType.GPU, engine.getLoadedModel()?.backend)
    }
}
