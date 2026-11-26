package io.androllm.core.accessibility.timing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceLoadMonitorTest {

    @Test
    fun `idle cool phone has k = 1`() {
        assertThat(LoadFactor.compute(LoadSample(thermalStatus = 0, freeRamFraction = 0.5, cpuBusy = 0.1))).isEqualTo(1.0)
        // Unknown readings count as no load.
        assertThat(LoadFactor.compute(LoadSample())).isEqualTo(1.0)
    }

    @Test
    fun `each load source adds its calibrated term`() {
        val base = LoadSample(thermalStatus = 0, freeRamFraction = 0.5)
        assertThat(LoadFactor.compute(base.copy(powerSave = true))).isWithin(1e-9).of(1.0 + LoadFactor.SAVER)
        assertThat(LoadFactor.compute(base.copy(modelGenerating = true, modelLoaded = true))).isWithin(1e-9).of(1.0 + LoadFactor.MODEL_GENERATING)
        assertThat(LoadFactor.compute(base.copy(modelLoaded = true))).isWithin(1e-9).of(1.0 + LoadFactor.MODEL_LOADED)
        assertThat(LoadFactor.compute(base.copy(thermalStatus = 2))).isWithin(1e-9).of(1.0 + LoadFactor.THERMAL_MODERATE)
        assertThat(LoadFactor.compute(base.copy(thermalStatus = 3))).isWithin(1e-9).of(1.0 + LoadFactor.THERMAL_SEVERE)
        assertThat(LoadFactor.compute(base.copy(freeRamFraction = 0.15))).isWithin(1e-9).of(1.0 + LoadFactor.MEM_TIGHT)
        assertThat(LoadFactor.compute(base.copy(lowMemory = true))).isWithin(1e-9).of(1.0 + LoadFactor.MEM_LOW)
        assertThat(LoadFactor.compute(base.copy(cpuBusy = 1.0))).isWithin(1e-9).of(1.0 + LoadFactor.CPU_MAX)
        assertThat(LoadFactor.compute(base.copy(cpuBusy = 0.75))).isWithin(1e-9).of(1.0 + LoadFactor.CPU_MAX / 2)
        assertThat(LoadFactor.compute(base.copy(cpuBusy = 0.4))).isEqualTo(1.0)
    }

    @Test
    fun `k is clamped to 3`() {
        val worst = LoadSample(thermalStatus = 6, lowMemory = true, powerSave = true, cpuBusy = 1.0, modelLoaded = true, modelGenerating = true, animatorScale = 2f)
        assertThat(LoadFactor.compute(worst)).isEqualTo(LoadFactor.K_MAX)
    }

    @Test
    fun `lower animation scale shortens, but never below the floor`() {
        assertThat(LoadFactor.animFactor(1f)).isEqualTo(1.0)
        assertThat(LoadFactor.animFactor(0.5f)).isWithin(1e-9).of(0.85)
        assertThat(LoadFactor.animFactor(0f)).isWithin(1e-9).of(0.7)
        // k never goes below 1 even with animations off (the floor of the clamp).
        assertThat(LoadFactor.compute(LoadSample(animatorScale = 0f))).isEqualTo(1.0)
        assertThat(LoadFactor.compute(LoadSample(powerSave = true, animatorScale = 0.5f))).isWithin(1e-9).of((1.0 + LoadFactor.SAVER) * 0.85)
    }

    @Test
    fun `monitor caches one sample for a second`() {
        var calls = 0
        var now = 0L
        val m = DeviceLoadMonitor({ calls++; LoadSample(powerSave = calls > 1) }, { now })
        assertThat(m.loadFactor()).isEqualTo(1.0)
        now = 500
        assertThat(m.loadFactor()).isEqualTo(1.0)
        assertThat(calls).isEqualTo(1)
        now = 1_500
        assertThat(m.loadFactor()).isGreaterThan(1.0)
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `a throwing source counts as no load`() {
        val m = DeviceLoadMonitor({ error("binder died") })
        assertThat(m.loadFactor()).isEqualTo(1.0)
    }

    @Test
    fun `proc stat parsing`() {
        val (total, idle) = CpuMeter.parseProcStat("cpu  100 0 50 800 50 0 0 0 0 0")!!
        assertThat(total).isEqualTo(1000)
        assertThat(idle).isEqualTo(850)
        assertThat(CpuMeter.parseProcStat("cpu0 1 2 3 4")).isNull()
        assertThat(CpuMeter.parseProcStat(null)).isNull()
        // comm with spaces and parentheses; utime=7, stime=3.
        val self = "1234 (my (odd) app) S 1 2 3 4 5 6 7 8 9 10 7 3 0 0 20 0"
        assertThat(CpuMeter.parseSelfTicks(self)).isEqualTo(10)
    }

    @Test
    fun `cpu meter uses the device line, falls back to this process`() {
        val lines = ArrayDeque(listOf("cpu  100 0 0 900", "cpu  150 0 0 950"))
        val dev = CpuMeter(procStat = { lines.removeFirst() }, selfStat = { null })
        assertThat(dev.busy()).isEqualTo(-1.0)
        assertThat(dev.busy()).isWithin(1e-9).of(0.5)

        var wall = 0L
        var ticks = 0L
        val self = CpuMeter(procStat = { null }, selfStat = { "1 (x) S 1 2 3 4 5 6 7 8 9 10 $ticks 0 0" }, wallMs = { wall }, cores = 2, clkTck = 100)
        assertThat(self.busy()).isEqualTo(-1.0)
        wall = 1_000; ticks = 100 // 1 s of CPU in 1 s wall on 2 cores = 50 %
        assertThat(self.busy()).isWithin(1e-9).of(0.5)
    }
}
