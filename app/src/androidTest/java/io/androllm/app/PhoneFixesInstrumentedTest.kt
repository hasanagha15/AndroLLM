package io.androllm.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.androllm.core.common.Result
import io.androllm.core.common.isSuccess
import io.androllm.core.memory.personal.AssistantInstructions
import io.androllm.core.memory.personal.PersonalContextSettings
import io.androllm.core.models.Model
import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.agent.DeviceContextProvider
import io.androllm.core.tools.planner.ToolPlanner
import io.androllm.core.tools.prompt.ToolPromptBuilder
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.tool.impl.BatteryTool
import io.androllm.core.tools.tool.impl.DeviceInfoTool
import io.androllm.core.tools.tool.impl.VolumeTool
import io.androllm.core.tools.tool.impl.WifiTool
import io.androllm.engine.api.DefaultEngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import io.androllm.engine.core.LiteRtLmEngine
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.ModelLoadConfig
import io.androllm.engine.models.NativeToolSpec
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Branch P: on-phone verification of the step-4 fixes WITHOUT the UI (the
 * phone's screen is locked with a credential, the app UI cannot be driven).
 * Drives the REAL engine + repository + prompt builders exactly as
 * ChatViewModel assembles a local turn:
 *   system = AssistantInstructions + tool advertisement (family call format;
 *   Gemma 4: header only + native tool declarations), then the user message.
 * Tools are NEVER executed here — only the parsed calls are logged.
 *
 * Run (never via connectedAndroidTest — it would uninstall the app):
 *   adb shell am instrument -w -e class io.androllm.app.PhoneFixesInstrumentedTest \
 *     -e model /sdcard/Android/data/io.androllm.app/files/models/<file>.litertlm \
 *     io.androllm.app.test/androidx.test.runner.AndroidJUnitRunner
 * Results: logcat tag PhoneFix.
 */
@RunWith(AndroidJUnit4::class)
class PhoneFixesInstrumentedTest {

    companion object {
        private const val TAG = "PhoneFix"
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val args get() = InstrumentationRegistry.getArguments()

    private fun log(msg: String) = Log.i(TAG, msg)

    @Test
    fun verifyStep4Fixes() = runBlocking {
        val path = args.getString("model")
        assumeTrue("pass -e model <path>", path != null && File(path).exists())
        val name = args.getString("name") ?: File(path!!).nameWithoutExtension.replace('_', ' ')
        val phases = (args.getString("phases") ?: "chat,bench,reload").split(',').toSet()

        val repository = DefaultEngineRepository(LiteRtLmEngine(context))
        repository.initialize()
        val model = Model(
            id = "p-" + File(path!!).name,
            name = name,
            filePath = path,
            fileSize = File(path).length(),
            family = args.getString("family") ?: "",
            contextLength = args.getString("ctx")?.toIntOrNull() ?: 8192
        )
        val load = repository.loadModel(model, ModelLoadConfig())
        log("LOAD $name -> ${if (load.isSuccess()) "ok" else "FAILED $load"}")
        if (!load.isSuccess()) return@runBlocking
        val ready = repository.engineState.value as? EngineState.Ready
        val info = ready?.model
        log(
            "READY backend=${info?.backend} family=${info?.family} template=${info?.templateSource} " +
                "nativeToolApi=${info?.nativeToolApi} ctx=${info?.contextLength} callFormat=${info?.toolCallInstruction?.take(60)}"
        )

        val registry = ToolRegistry().apply {
            register(BatteryTool(context))
            register(DeviceInfoTool(context))
            register(VolumeTool(context))
            register(WifiTool(context))
        }
        val planner = ToolPlanner(
            registry,
            AutomationSettingsStore(context),
            repository,
            AgentContextBuilder(DeviceContextProvider(context), AgentVariableStore()),
            io.androllm.core.tools.router.ToolRouter()
        )
        val builder = ToolPromptBuilder(planner)
        val ctx = info?.contextLength ?: 2048
        val systemBudget = (ctx - (ctx / 3) - 256).coerceAtLeast(0) * 4

        suspend fun turn(label: String, prompt: String) {
            val instructions = AssistantInstructions.build(PersonalContextSettings(), (systemBudget / 3).coerceAtLeast(1))
            val native = info?.nativeToolApi == true
            val ad = builder.advertisement(
                maxChars = minOf(systemBudget - instructions.length, info?.toolAdvertisementCapChars ?: Int.MAX_VALUE),
                query = prompt,
                callInstruction = info?.toolCallInstruction,
                listTools = !native
            )
            val nativeTools = if (native && ad != null) {
                builder.routedSpecs(prompt).map { NativeToolSpec(it.name, it.description, it.parameters.toString()) }
            } else emptyList()
            val messages = buildList {
                add(ChatPromptMessage("system", instructions))
                ad?.let { add(ChatPromptMessage("system", it)) }
                add(ChatPromptMessage("user", prompt))
            }
            val started = System.currentTimeMillis()
            repository.generateChat(
                messages,
                addAssistant = true,
                config = GenerationConfig(
                    maxTokens = 256, temperature = 0.3f,
                    sessionKey = "phonefix:$label", nativeTools = nativeTools
                )
            )
            val state = repository.generationState.value
            val text = (state as? GenerationState.Completed)?.text ?: "<${state::class.simpleName}: ${(state as? GenerationState.Failed)?.message}>"
            val calls = repository.takeLastNativeToolCalls()
            val stats = repository.performanceStats.value
            log(
                "TURN[$label] prompt='$prompt' ms=${System.currentTimeMillis() - started} " +
                    "nativeCalls=${calls.size} ${calls.joinToString { it.name + it.argumentsJson }} " +
                    "prefillTok=${stats?.promptTokens} ttft=${stats?.firstTokenMs} reply='${text.replace('\n', ' ').take(300)}'"
            )
        }

        if ("chat" in phases) {
            turn("ru", "Какая столица Франции? Ответь одним предложением.")
            turn("translit", "privet, kak dela")
            turn("tool", "открой настройки Wi-Fi")
        }

        if ("reload" in phases) {
            repository.unloadForMemoryPressure()
            log("MEMORY-PRESSURE unload done; engineState=${repository.engineState.value::class.simpleName}")
            turn("after-unload", "privet")
        }

        if ("bench" in phases) {
            val before = repository.engineState.value as? EngineState.Ready
            when (val r = repository.benchmarkBackends()) {
                is Result.Success -> r.data.forEach {
                    log(
                        "BENCH ${it.backendLabel} ok=${it.succeeded} prefill=${"%.1f".format(it.prefillTokensPerSecond)} tok/s " +
                            "(${it.prefillTokens} tok) decode=${"%.1f".format(it.decodeTokensPerSecond)} tok/s (${it.decodeTokens} tok) " +
                            "ttft=${it.firstTokenMs}ms init=${it.initTimeMs}ms err=${it.error}"
                    )
                }
                is Result.Error -> log("BENCH failed: ${r.exception.message}")
                else -> log("BENCH: $r")
            }
            val after = repository.engineState.value as? EngineState.Ready
            log("BENCH restore: before=${before?.model?.backend} after=${after?.model?.backend}")
        }

        if ("gpureload" in phases) {
            for (i in 1..2) {
                repository.unloadModel()
                val again = repository.loadModel(model, ModelLoadConfig())
                val st = repository.engineState.value as? EngineState.Ready
                log("GPU-RELOAD #$i -> ${if (again.isSuccess()) "ok" else "FAILED"} backend=${st?.model?.backend} ctx=${st?.model?.contextLength}")
            }
        }
        repository.release()
    }
}
