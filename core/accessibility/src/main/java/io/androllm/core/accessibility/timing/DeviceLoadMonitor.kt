package io.androllm.core.accessibility.timing

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.GenerationState
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Branch T: one reading of what slows screen transitions down on this phone.
 * Numbers only — nothing about apps, screens or the user.
 */
data class LoadSample(
    /** PowerManager thermal status 0 (none) … 6 (shutdown); -1 = unknown (API < 29). */
    val thermalStatus: Int = -1,
    /** Free RAM share (MemoryInfo.availMem / totalMem), 0..1; -1 = unknown. */
    val freeRamFraction: Double = -1.0,
    /** MemoryInfo.lowMemory (the system is close to killing background apps). */
    val lowMemory: Boolean = false,
    /** Battery saver (PowerManager.isPowerSaveMode). */
    val powerSave: Boolean = false,
    /** Busy CPU share since the previous sample, 0..1 (device-wide if readable, else this process); -1 = unknown. */
    val cpuBusy: Double = -1.0,
    /** A local model is loaded in this process (holds 2–3 GB, keeps the GPU warm). */
    val modelLoaded: Boolean = false,
    /** The local model is generating right now (GPU + memory bandwidth contention). */
    val modelGenerating: Boolean = false,
    /** Settings.Global.ANIMATOR_DURATION_SCALE (1.0 = default, 0 = animations off). */
    val animatorScale: Float = 1f
)

/**
 * Load factor k (1.0 … 3.0): how much longer than on an idle, cool phone a
 * screen transition is expected to take now. Additive terms, calibrated on
 * the owner's phone (branch T measurements, see T-result.md):
 *
 *   k = (1 + thermal + memory + saver + cpu + model) × anim, clamped to [K_MIN, K_MAX]
 *
 * Pure function — unit tested.
 */
object LoadFactor {
    const val K_MIN = 1.0
    const val K_MAX = 3.0

    // Coefficients — branch T calibration on the owner's S24 Ultra (26.11, see T-result.md).
    // Measured on app launches (am start -W, p50/p95 ratios vs. idle without a model):
    //   model resident on GPU ×1.16–1.38, generating ×2.1 (cold) … ×3.0 (warm),
    //   battery saver +3–7 %. In-app transitions move far less (see AdaptiveStepTiming.sensitivity).
    const val THERMAL_LIGHT = 0.0     // status 1: no measurable effect
    const val THERMAL_MODERATE = 0.10 // status 2 (the phone's usual state while charging)
    const val THERMAL_SEVERE = 0.30   // status 3 (reached during sustained generation)
    const val THERMAL_CRITICAL = 0.80 // status ≥ 4 (not reached; extrapolated)
    const val MEM_LOW = 0.40          // lowMemory or < 10 % free (LMK kills → cold starts)
    const val MEM_TIGHT = 0.15        // < 20 % free
    const val SAVER = 0.10
    const val CPU_MAX = 0.40          // linear from CPU_KNEE to 100 % busy
    const val CPU_KNEE = 0.50
    const val MODEL_LOADED = 0.25
    const val MODEL_GENERATING = 1.00

    fun compute(s: LoadSample): Double {
        var k = 1.0
        k += when {
            s.thermalStatus >= 4 -> THERMAL_CRITICAL
            s.thermalStatus == 3 -> THERMAL_SEVERE
            s.thermalStatus == 2 -> THERMAL_MODERATE
            s.thermalStatus == 1 -> THERMAL_LIGHT
            else -> 0.0
        }
        k += when {
            s.lowMemory || (s.freeRamFraction in 0.0..0.10) -> MEM_LOW
            s.freeRamFraction in 0.0..0.20 -> MEM_TIGHT
            else -> 0.0
        }
        if (s.powerSave) k += SAVER
        if (s.cpuBusy > CPU_KNEE) k += CPU_MAX * ((s.cpuBusy - CPU_KNEE) / (1.0 - CPU_KNEE)).coerceIn(0.0, 1.0)
        k += when {
            s.modelGenerating -> MODEL_GENERATING
            s.modelLoaded -> MODEL_LOADED
            else -> 0.0
        }
        k *= animFactor(s.animatorScale)
        return k.coerceIn(K_MIN, K_MAX)
    }

    /**
     * Transition time ≈ fixed work + animation. Measured (branch T): all three
     * scales at 0.5 → in-app transitions ×0.58 (p50) / ×0.69 (p95); launch
     * TotalTime (first frame) barely moves. Linear fit through (1, 1) and
     * (0.5, 0.7): 0.4 + 0.6 × scale; larger scales lengthen it.
     * Note (review R7): k is clamped to ≥ [K_MIN], so on an idle phone a lower
     * scale does not shorten the waits — it only offsets other load terms;
     * the learned per-transition times pick the faster transitions up anyway.
     */
    fun animFactor(scale: Float): Double = when {
        scale.isNaN() || scale < 0f -> 1.0
        scale >= 1f -> 1.0 + 0.5 * (scale - 1f).toDouble()
        else -> 0.4 + 0.6 * scale.toDouble()
    }
}

/** Where load readings come from (Android in production, a fixed sample in tests). */
fun interface LoadSource {
    fun sample(): LoadSample
}

/**
 * Branch T: samples thermal state, free RAM, battery saver, CPU load and the
 * local model's state, and turns them into the load factor k. Readings are
 * cached for [CACHE_MS] (cheap: a few binder calls + one /proc read). Nothing
 * is logged or stored.
 */
@Singleton
class DeviceLoadMonitor internal constructor(
    private val source: LoadSource,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        engine: EngineRepository
    ) : this(AndroidLoadSource(context, engine))

    @Volatile private var cached: Pair<Long, LoadSample>? = null

    fun sample(): LoadSample {
        val now = clock()
        cached?.let { (at, s) -> if (now - at < CACHE_MS) return s }
        val s = runCatching { source.sample() }.getOrDefault(LoadSample())
        cached = now to s
        return s
    }

    /** Current load factor k in [LoadFactor.K_MIN, LoadFactor.K_MAX]. */
    fun loadFactor(): Double = LoadFactor.compute(sample())

    /** Review R5: the same, sampled off the main thread (binder calls + /proc read). */
    suspend fun loadFactorAsync(): Double = withContext(Dispatchers.Default) { loadFactor() }

    companion object {
        const val CACHE_MS = 1_000L

        /** A monitor that always reports [sample] (tests, previews). */
        fun fixed(sample: LoadSample = LoadSample()) = DeviceLoadMonitor({ sample })
    }
}

/** Production readings. Every source is optional: an unreadable one counts as "no load". */
internal class AndroidLoadSource(
    private val context: Context,
    private val engine: EngineRepository
) : LoadSource {
    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val cpu = CpuMeter()

    override fun sample(): LoadSample {
        val mem = activity?.let { am -> ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) } }
        val engineState = engine.engineState.value
        return LoadSample(
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus ?: -1 else -1,
            freeRamFraction = mem?.takeIf { it.totalMem > 0 }?.let { it.availMem.toDouble() / it.totalMem } ?: -1.0,
            lowMemory = mem?.lowMemory == true,
            powerSave = power?.isPowerSaveMode == true,
            cpuBusy = cpu.busy(),
            modelLoaded = engineState is EngineState.Ready || engineState is EngineState.Generating,
            modelGenerating = engineState is EngineState.Generating || engine.generationState.value is GenerationState.Generating,
            animatorScale = runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
        )
    }
}

/**
 * CPU busy share between two calls. Device-wide /proc/stat is not readable
 * by apps on Android 8+, so it falls back to this process (/proc/self/stat:
 * the local model runs in-process, which is the load we cause ourselves).
 */
internal class CpuMeter(
    private val procStat: () -> String? = { runCatching { File("/proc/stat").bufferedReader().use { it.readLine() } }.getOrNull() },
    private val selfStat: () -> String? = { runCatching { File("/proc/self/stat").readText() }.getOrNull() },
    private val wallMs: () -> Long = SystemClock::elapsedRealtime,
    private val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    private val clkTck: Long = 100L
) {
    private var prevTotal = -1L
    private var prevIdle = -1L
    private var prevSelf = -1L
    private var prevWall = -1L

    @Synchronized
    fun busy(): Double {
        parseProcStat(procStat())?.let { (total, idle) ->
            val r = if (prevTotal >= 0 && total > prevTotal) 1.0 - (idle - prevIdle).toDouble() / (total - prevTotal) else -1.0
            prevTotal = total; prevIdle = idle
            return r.coerceIn(-1.0, 1.0)
        }
        val ticks = parseSelfTicks(selfStat()) ?: return -1.0
        val now = wallMs()
        val r = if (prevSelf >= 0 && now > prevWall) {
            val cpuMs = (ticks - prevSelf) * 1000.0 / clkTck
            (cpuMs / ((now - prevWall).toDouble() * cores)).coerceIn(0.0, 1.0)
        } else -1.0
        prevSelf = ticks; prevWall = now
        return r
    }

    companion object {
        /** "cpu  user nice system idle iowait irq softirq …" → (total, idle+iowait). */
        fun parseProcStat(line: String?): Pair<Long, Long>? {
            if (line == null || !line.startsWith("cpu ")) return null
            val v = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (v.size < 4) return null
            val idle = v[3] + (v.getOrNull(4) ?: 0L)
            return v.sum() to idle
        }

        /** utime + stime (fields 14, 15) of /proc/self/stat; the comm field may contain spaces. */
        fun parseSelfTicks(stat: String?): Long? {
            if (stat == null) return null
            val rest = stat.substringAfterLast(')', "").trim().split(' ')
            // rest[0] = state (field 3) → utime = field 14 = rest[11], stime = rest[12].
            val u = rest.getOrNull(11)?.toLongOrNull() ?: return null
            val s = rest.getOrNull(12)?.toLongOrNull() ?: return null
            return u + s
        }
    }
}
