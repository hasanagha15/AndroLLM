package io.androllm.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration as WorkConfiguration
import dagger.hilt.android.HiltAndroidApp
import io.androllm.core.cloud.ProviderHealthMonitor
import io.androllm.core.memory.background.MemoryBackgroundScheduler
import io.androllm.engine.api.EngineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Application class for AndroLLM.
 *
 * Registers ComponentCallbacks2 to keep the loaded model resident in memory
 * during background operation, only releasing under critical memory pressure.
 * Also wires WorkManager to Hilt (so memory background jobs get their
 * dependencies) and schedules the opportunistic memory maintenance loop.
 */
@HiltAndroidApp
class AndroLLMApplication : Application(), WorkConfiguration.Provider {

    @Inject
    lateinit var engineRepository: EngineRepository

    @Inject
    lateinit var providerHealthMonitor: ProviderHealthMonitor

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var memoryBackgroundScheduler: MemoryBackgroundScheduler

    @Inject
    lateinit var analyticsSyncScheduler: io.androllm.app.sync.AnalyticsSyncScheduler

    @Inject
    lateinit var analyticsSyncTrigger: io.androllm.app.sync.AnalyticsSyncTrigger

    // Branch L: App Atlas — package receiver, start-up incremental sync, weekly sweep.
    @Inject
    lateinit var appAtlasStarter: io.androllm.core.appatlas.sync.AppAtlasStarter

    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder().setWorkerFactory(workerFactory).build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        io.androllm.engine.diagnostics.StartupProfiler.markAppStart()

        // NPU (Qualcomm QNN) pre-init: the Hexagon DSP loads libQnnHtpV81Skel.so
        // via FastRPC from ADSP_LIBRARY_PATH, and libQnnHtp.so reads that env
        // var ONCE when it is dlopened. Seeding here (before any LiteRT lib
        // load) is mandatory — setting it later in engine init is too late and
        // the NPU init silently fails. The device's own skel dirs are included
        // so the DSP can fall back to the vendor copies; every entry is
        // best-effort and harmless when absent.
        seedNpuLibraryPaths()

        // Background health probing — LAZY: start only after first chat/cloud use to avoid network on cold start
        // providerHealthMonitor.start() deferred to first CloudGateway access (see ProviderHealthMonitor lazy init)

        // Memory housekeeping — LAZY: WorkManager init is deferred; schedule only after first memory write or on next foreground
        // memoryBackgroundScheduler.schedule() deferred to MemoryRepository first write
        // Keep NPU env seeding on critical path (must be before first dlopen) — already done above

        // Analytics sync — cheap unique-work enqueue (KEEP); the worker itself
        // no-ops unless signed in AND web-connected. No data leaves the device before approval.
        runCatching { analyticsSyncScheduler.schedule() }
            .onFailure { Timber.w("[AnalyticsSync] schedule failed: ${it.message}") }
        // Near-real-time path: each recorded request/generation enqueues a
        // debounced upload (~8s), so the dashboard reflects usage in <10s.
        runCatching { analyticsSyncTrigger.bind() }
            .onFailure { Timber.w("[AnalyticsSync] trigger bind failed: ${it.message}") }

        // Branch L: App Atlas (the first sync waits 30 s, off the cold-start path).
        runCatching { appAtlasStarter.start() }
            .onFailure { Timber.w("[AppAtlas] start failed: ${it.message}") }

        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                        Timber.w("[Engine] Memory pressure: RUNNING_LOW — keeping model loaded")
                    }
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                        Timber.w("[Engine] Memory pressure: RUNNING_CRITICAL — unloading model")
                        noteResidentSharePressure(io.androllm.engine.utils.ResidentShareMemory.Pressure.RUNNING_CRITICAL)
                        appScope.launch { engineRepository.unloadForMemoryPressure() }
                    }
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                        Timber.w("[Engine] Memory pressure: COMPLETE — unloading model")
                        appScope.launch { engineRepository.unloadForMemoryPressure() }
                    }
                    else -> {
                        Timber.d("[Engine] Memory trim level: $level — keeping model loaded")
                    }
                }
            }

            @Deprecated("Deprecated in API level 1")
            override fun onLowMemory() {
                Timber.w("[Engine] onLowMemory — unloading model")
                noteResidentSharePressure(io.androllm.engine.utils.ResidentShareMemory.Pressure.LOW_MEMORY)
                appScope.launch { engineRepository.unloadForMemoryPressure() }
            }

            override fun onConfigurationChanged(newConfig: Configuration) { /* no-op */ }
        })
    }

    /**
     * Sets the DSP/vendor library search paths the Qualcomm QNN backend needs
     * to reach the Hexagon skeleton. Must run before the first dlopen of any
     * LiteRT/QNN library, hence in [onCreate].
     */
    private fun seedNpuLibraryPaths() {
        runCatching {
            val nativeLibDir = applicationInfo.nativeLibraryDir
            // 16 KB packaging (uncompressed jniLibs): nativeLibraryDir is empty
            // and the QNN libs + Hexagon skel are staged here instead (see
            // NpuLibraryDir). The env var only needs the path; the copy itself
            // runs in the background and the engine waits for it before NPU init.
            val stagedNpuDir = io.androllm.engine.backend.NpuLibraryDir.stagingPath(this)
            if (io.androllm.engine.backend.NpuLibraryDir.isQualcommSoc()) {
                Thread({
                    io.androllm.engine.backend.NpuLibraryDir.ensureStaged(this)
                }, "npu-lib-stage").apply { isDaemon = true }.start()
            }
            val paths = listOf(
                stagedNpuDir,
                nativeLibDir,
                // This device (SM8845 / OnePlus) keeps its V81 skel here.
                "/odm/lib64/aiframe/cdsp/unsigned",
                "/odm/lib64/aiframe/cdsp/signed",
                "/odm/lib64/aiframe",
                "/odm/lib64",
                "/vendor/dsp/cdsp",
                "/vendor/lib64",
                "/vendor/lib64/snap",
                "/system/lib64",
                "/system/vendor/lib64"
            ).filter { it.isNotBlank() }
            val joined = paths.joinToString(":")
            android.system.Os.setenv("ADSP_LIBRARY_PATH", joined, true)
            android.system.Os.setenv("LD_LIBRARY_PATH", joined, true)
            Timber.d("[NPU] ADSP_LIBRARY_PATH=$joined")
        }.onFailure { e ->
            Timber.w("[NPU] Could not seed ADSP_LIBRARY_PATH: ${e.message}")
        }
    }

    /**
     * Branch U it.3 (B3): a foreground RUNNING_CRITICAL / onLowMemory soon after a
     * reduced-share Gemma "E" load is ONE event toward the full resident share.
     * TRIM_MEMORY_COMPLETE (cached in the background) is never reported.
     */
    private fun noteResidentSharePressure(pressure: io.androllm.engine.utils.ResidentShareMemory.Pressure) {
        runCatching {
            val state = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(state)
            val foreground = state.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            if (io.androllm.engine.utils.ResidentShareMemory.onMemoryPressure(pressure, foreground, System.currentTimeMillis())) {
                Timber.w("[Engine] memory pressure soon after load — 1 event toward the full resident share")
            }
        }
    }
}
