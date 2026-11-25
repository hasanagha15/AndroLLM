package io.androllm.engine

import io.androllm.core.common.Result
import io.androllm.core.models.Model
import io.androllm.engine.api.DefaultEngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.backend.BackendCapabilities
import io.androllm.engine.compat.ChatTemplateCompat
import io.androllm.engine.compat.ChatTemplateRenderer
import io.androllm.engine.compat.ChatTemplates
import io.androllm.engine.compat.Gemma4PromptMirror
import io.androllm.engine.compat.ModelFamily
import io.androllm.engine.compat.PromptShapeCheck
import io.androllm.engine.compat.TemplateSelector
import io.androllm.engine.core.NativeToolDeclarations
import io.androllm.engine.models.BackendType
import io.androllm.engine.models.BenchmarkResult
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineCapabilities
import io.androllm.engine.models.EngineConfig
import io.androllm.engine.models.EngineDebugInfo
import io.androllm.engine.models.EngineModelInfo
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.ModelLoadConfig
import io.androllm.engine.models.NativeToolSpec
import io.androllm.engine.models.StreamChunk
import io.androllm.engine.utils.RuntimeCachePruner
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Opus gate of branch P (review P-opus.md). The
 * gate probes P1–P5 are ported with the assertions inverted: each one now
 * pins the FIXED behaviour.
 */
class PGateRegressionTest {

    private class StubEngine : InferenceEngine {
        val loads = mutableListOf<String>()
        val failIds = mutableSetOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var streams = 0
        private var loaded: EngineModelInfo? = null
        override val capabilities = EngineCapabilities(name = "S", version = "1", backend = BackendType.CPU)
        override val backendCapabilities: StateFlow<BackendCapabilities> = MutableStateFlow(BackendCapabilities.UNKNOWN)
        private val st = MutableStateFlow<EngineState>(EngineState.Unloaded)
        override val engineState: Flow<EngineState> = st
        override val stats: Flow<EngineStats?> = MutableStateFlow(null)
        override suspend fun initialize(config: EngineConfig): Result<Unit> = Result.Success(Unit)
        override fun isLoaded() = loaded != null
        override fun getLoadedModel() = loaded
        override suspend fun loadModel(model: Model, config: ModelLoadConfig): Result<EngineModelInfo> {
            loads += model.id
            gate?.await()
            if (model.id in failIds) {
                loaded = null
                return Result.error("RAM")
            }
            val i = EngineModelInfo(model.id, model.filePath ?: "", 4096, 0, BackendType.CPU, generalName = model.name)
            loaded = i
            st.value = EngineState.Ready(i)
            return Result.Success(i)
        }
        override suspend fun unloadModel(): Result<Unit> { loaded = null; return Result.Success(Unit) }
        override fun tokenStream(prompt: String, config: GenerationConfig): Flow<Result<StreamChunk>> = flow {
            streams++
            emit(Result.Success(StreamChunk("ok", false)))
            emit(Result.Success(StreamChunk("", true, generatedTokens = 1)))
        }
        override suspend fun buildChatPrompt(messages: List<ChatPromptMessage>, addAssistant: Boolean): Result<String> = Result.Success("")
        override suspend fun generate(prompt: String, config: GenerationConfig): Result<String> = Result.Success("ok")
        override fun generateChatStream(messages: List<ChatPromptMessage>, addAssistant: Boolean, config: GenerationConfig) =
            tokenStream("", config)
        override fun cancel(): Result<Unit> = Result.Success(Unit)
        override fun benchmark(iterations: Int): Flow<Result<BenchmarkResult>> = flow { }
        override suspend fun resetChat(): Result<Unit> = Result.Success(Unit)
        override suspend fun getDebugInfo(): Result<EngineDebugInfo?> = Result.Success(null)
        override fun release() { loaded = null }
    }

    private val a = Model(id = "A", name = "A", filePath = "/a")
    private val b = Model(id = "B", name = "B", filePath = "/b")
    private val chat = listOf(ChatPromptMessage(role = "user", content = "Hi"))
    private val cfg = ModelLoadConfig(runSelfTest = false)

    /** P1 / N3: a failed user load of B must not bring A back on the next turn. */
    @Test
    fun `failed user load clears the pending memory-pressure reload`() = runTest {
        val e = StubEngine()
        val repo = DefaultEngineRepository(e)
        repo.loadModel(a, cfg)
        repo.unloadForMemoryPressure()
        e.failIds += "B"
        assertTrue(repo.loadModel(b, cfg) is Result.Error)
        val turn = repo.generateChat(chat)
        assertEquals(listOf("A", "B"), e.loads)
        assertTrue(turn is Result.Error)
    }

    /** P2 / N2: Stop pressed during the auto-reload cancels the generation after it. */
    @Test
    fun `stop during auto reload cancels the generation`() = runTest {
        val e = StubEngine()
        val repo = DefaultEngineRepository(e)
        repo.loadModel(a, cfg)
        repo.unloadForMemoryPressure()
        e.gate = CompletableDeferred()
        val job = async { repo.generateChat(chat) }
        yield()
        repo.cancelGeneration()
        e.gate!!.complete(Unit)
        job.await()
        assertEquals("no generation after a cancelled reload", 0, e.streams)
        assertTrue(repo.generationState.value is GenerationState.Cancelled)
        assertTrue("the model itself is reloaded", e.isLoaded())
    }

    /** P3 / N2: a failed auto-reload is attempted once; later requests fail with a clear error. */
    @Test
    fun `failed auto reload backs off and reports an error`() = runTest {
        val e = StubEngine()
        val repo = DefaultEngineRepository(e)
        repo.loadModel(a, cfg)
        repo.unloadForMemoryPressure()
        e.failIds += "A"
        val first = repo.generateChat(chat)
        val second = repo.generateChat(chat)
        assertEquals(listOf("A", "A"), e.loads)
        assertTrue(first is Result.Error && second is Result.Error)
        val msg = (repo.generationState.value as GenerationState.Failed).message
        assertTrue(msg, msg.contains("Could not reload") && msg.contains("Load the model again"))
    }

    /** P4 / B1: the default debug log is built from placeholders — no message content at all. */
    @Test
    fun `default prompt skeleton contains no message content`() {
        val secret = "SMS from bank: code 482913 for transfer"
        val systemText = "PERSONAL CONTEXT: lives at Example street 5"
        val turns = listOf("system" to systemText + "S".repeat(3000), "user" to "UNTRUSTED_DATA $secret")
        val placeholders = PromptShapeCheck.placeholders(turns)
        val qwen = ChatTemplateRenderer().render(
            template = ChatTemplateCompat.partsAware(ChatTemplates.qwen3),
            messages = placeholders.map { ChatTemplateRenderer.RenderMessage(it.first, it.second) },
            bosToken = "", eosToken = "<|im_end|>", addGenerationPrompt = true,
            extraContext = mapOf("enable_thinking" to false)
        )
        val gemma = Gemma4PromptMirror.render(placeholders, addGenerationPrompt = true)
        for (skeleton in listOf(qwen, gemma)) {
            val logged = PromptShapeCheck.excerpt(skeleton) + PromptShapeCheck.describeTurns(turns)
            assertFalse(logged.contains("482913"))
            assertFalse(logged.contains("Example street"))
            assertFalse(logged.contains("UNTRUSTED_DATA"))
            assertTrue("shape stays checkable", PromptShapeCheck.check(skeleton).endsWithAssistantHeader)
        }
        assertTrue(qwen.contains("[user: ${turns[1].second.length} chars]"))
        assertEquals("system:${turns[0].second.length}, user:${turns[1].second.length}", PromptShapeCheck.describeTurns(turns))
        assertEquals("'<|turn>model\\n'", PromptShapeCheck.visible("<|turn>model\n"))
    }

    /** P5 / N4: a stray quoted 'text' no longer counts as parts-aware. */
    @Test
    fun `parts-aware detection needs the strict signature and a verified family`() {
        val stringOnly = "{% for m in messages %}{% if m.type == 'text' %}{% endif %}" +
            "{{ '<|im_start|>' + m.role + '\\n' + m.content }}{% endfor %}"
        assertFalse(TemplateSelector.isPartsAware(stringOnly))
        assertEquals(
            TemplateSelector.Source.FAMILY_OVERRIDE,
            TemplateSelector.select(ModelFamily.QWEN3, stringOnly, "Qwen3 1.7B").source
        )
        // Parts-aware but not a verified family → the official override stays.
        val partsAwareQwen = "{% for m in messages %}{% for p in m['content'] %}{% if p['type'] == 'text' %}" +
            "{{ p['text'] }}{% endif %}{% endfor %}{% endfor %}"
        assertTrue(TemplateSelector.isPartsAware(partsAwareQwen))
        assertEquals(
            TemplateSelector.Source.FAMILY_OVERRIDE,
            TemplateSelector.select(ModelFamily.QWEN3, partsAwareQwen, "Qwen3.5").source
        )
    }

    /** N1: native tool declarations are reserved in the system-token overhead. */
    @Test
    fun `native tool declarations have a token cost`() {
        val specs = listOf(
            NativeToolSpec("set_wifi", "Turn Wi-Fi on or off", "{\"type\":\"object\",\"properties\":{\"enabled\":{\"type\":\"boolean\"}}}"),
            NativeToolSpec("get_battery", "Battery level", "{}")
        )
        val tokens = NativeToolDeclarations.estimatedTokens(specs)
        val chars = specs.sumOf { NativeToolDeclarations.descriptionJson(it).length }
        assertTrue("tokens=$tokens chars=$chars", tokens >= chars / 4 && tokens > 0)
        assertEquals(0, NativeToolDeclarations.estimatedTokens(emptyList()))
    }

    /** N8: at most 2 context caches per model; legacy flat files and other models untouched/removed correctly. */
    @Test
    fun `runtime cache pruner keeps two context dirs per model`() {
        val cache = Files.createTempDirectory("p-cache").toFile()
        try {
            val root = File(cache, "litertlm")
            val model = "Gemma_4.litertlm"
            fun put(dir: File, name: String, age: Long): File =
                File(dir.apply { mkdirs() }, name).apply { writeText("x"); setLastModified(System.currentTimeMillis() - age) }
            val legacy = put(cache, "${model}_1_2_mldrift_program_cache.bin", 0)
            val dbLock = put(cache, "androllm.db.lck", 0)
            val old = put(File(root, "ctx512"), "${model}_1_2_mldrift_program_cache.bin", 300_000)
            val recent = put(File(root, "ctx4096"), "${model}_1_2_mldrift_program_cache.bin", 100_000)
            val otherModel = put(File(root, "ctx2048"), "Qwen3.litertlm_9_9.xnnpack_cache", 900_000)
            val current = File(root, "ctx8192").apply { mkdirs() }

            val pruned = RuntimeCachePruner.prune(root, cache, model, current)

            assertFalse("legacy flat file of the model removed", legacy.exists())
            assertTrue("unrelated cache file untouched", dbLock.exists())
            assertFalse("oldest context cache removed", old.exists())
            assertFalse("empty ctx dir removed", File(root, "ctx512").exists())
            assertTrue("most recent other context kept", recent.exists())
            assertTrue("other model untouched", otherModel.exists())
            assertTrue("current dir kept", current.exists())
            assertEquals(2, pruned.deletedFiles)
        } finally {
            cache.deleteRecursively()
        }
    }
}
