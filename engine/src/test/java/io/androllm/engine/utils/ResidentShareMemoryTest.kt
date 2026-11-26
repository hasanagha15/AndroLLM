package io.androllm.engine.utils

import io.androllm.engine.utils.ResidentShareMemory.Pressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Branch U it.2/it.3 (gate Z7, B3): the reduced resident share corrects itself — carefully. */
class ResidentShareMemoryTest {

    private val map = mutableMapOf<String, String>()
    private val mib = 1024L * 1024L
    private val day = 24 * 60 * 60_000L

    @Before
    fun setUp() {
        map.clear()
        ResidentShareMemory.resetForTest(object : ResidentShareMemory.Store {
            override fun get(key: String) = map[key]
            override fun put(key: String, value: String) { map[key] = value }
            override fun clear() = map.clear()
        }, version = 7L)
    }

    private fun share(nowMs: Long) = ResidentShareMemory.effectiveFraction("e2b", 0.55, nowMs)

    @Test
    fun `measurement excludes file-backed pages - private dirty plus graphics`() {
        // 1.2 GB private dirty + 300 MB graphics; mmapped .litertlm pages are not part of it.
        assertEquals((1_200L + 300L) * mib, MemoryEstimator.nonFileBytes(1_200 * 1024, (300 * 1024).toString()))
        assertEquals(1_200L * mib, MemoryEstimator.nonFileBytes(1_200 * 1024, null))
        assertEquals(1_200L * mib, MemoryEstimator.nonFileBytes(1_200 * 1024, "n/a"))
    }

    @Test
    fun `a big full PSS of file pages never counts - only non-file memory is compared`() {
        // Non-file memory within tolerance → no event, even if the full PSS was 3 GB.
        assertFalse(ResidentShareMemory.afterLoad("e2b", 0.55, nonFileBytes = 1_900 * mib, estimateBytes = 1_800 * mib, nowMs = 0))
        assertEquals(0.55, share(0), 1e-9)
    }

    @Test
    fun `one overshoot is not enough - two events switch to the full share`() {
        assertTrue(ResidentShareMemory.afterLoad("e2b", 0.55, 2_400 * mib, 1_800 * mib, nowMs = 0))
        assertEquals(0.55, share(1_000), 1e-9)
        assertTrue(ResidentShareMemory.afterLoad("e2b", 0.55, 2_400 * mib, 1_800 * mib, nowMs = 2_000))
        assertEquals(1.0, share(3_000), 1e-9)
        assertEquals(0.66, ResidentShareMemory.effectiveFraction("e4b", 0.66, 3_000), 1e-9)
    }

    @Test
    fun `TRIM_MEMORY_COMPLETE and background pressure never count`() {
        ResidentShareMemory.afterLoad("e2b", 0.55, 0, 0, nowMs = 0)
        assertFalse(ResidentShareMemory.onMemoryPressure(Pressure.COMPLETE, foreground = true, nowMs = 1_000))
        assertFalse(ResidentShareMemory.onMemoryPressure(Pressure.COMPLETE, foreground = false, nowMs = 1_000))
        assertFalse(ResidentShareMemory.onMemoryPressure(Pressure.RUNNING_CRITICAL, foreground = false, nowMs = 1_000))
        assertFalse(ResidentShareMemory.onMemoryPressure(Pressure.OTHER, foreground = true, nowMs = 1_000))
        assertTrue(map.isEmpty())
    }

    @Test
    fun `foreground critical pressure soon after load counts, late pressure does not`() {
        ResidentShareMemory.afterLoad("e2b", 0.55, 0, 0, nowMs = 0)
        assertFalse(ResidentShareMemory.onMemoryPressure(Pressure.LOW_MEMORY, true, ResidentShareMemory.PRESSURE_WINDOW_MS + 1))
        assertTrue(ResidentShareMemory.onMemoryPressure(Pressure.RUNNING_CRITICAL, true, 60_000))
        assertEquals(0.55, share(60_001), 1e-9)
        assertTrue(ResidentShareMemory.onMemoryPressure(Pressure.LOW_MEMORY, true, 120_000))
        assertEquals(1.0, share(120_001), 1e-9)
    }

    @Test
    fun `the mark expires after 7 days and on an app update`() {
        ResidentShareMemory.onLoadOom("e2b", 0.55, nowMs = 0)
        ResidentShareMemory.onLoadOom("e2b", 0.55, nowMs = 1_000)
        assertEquals(1.0, share(2 * day), 1e-9)
        assertEquals(0.55, share(1_000 + 7 * day + 1), 1e-9)
        // Fresh mark, then the app is updated: evidence from the old version is ignored.
        ResidentShareMemory.onLoadOom("e2b", 0.55, nowMs = 10 * day)
        ResidentShareMemory.onLoadOom("e2b", 0.55, nowMs = 10 * day + 1)
        assertEquals(1.0, share(10 * day + 2), 1e-9)
        ResidentShareMemory.appVersion = 8L
        assertEquals(0.55, share(10 * day + 3), 1e-9)
    }

    @Test
    fun `an old single event expires instead of adding up`() {
        ResidentShareMemory.onLoadOom("e2b", 0.55, nowMs = 0)
        ResidentShareMemory.onLoadOom("e2b", 0.55, nowMs = 8 * day)
        assertEquals(0.55, share(8 * day + 1), 1e-9)
    }

    @Test
    fun `developer reset forgets everything, full-share models are never marked`() {
        ResidentShareMemory.onLoadOom("qwen", 1.0, nowMs = 0)
        assertTrue(map.isEmpty())
        ResidentShareMemory.onLoadOom("e2b", 0.55, nowMs = 0)
        ResidentShareMemory.onLoadOom("e2b", 0.55, nowMs = 1)
        assertEquals(1.0, share(2), 1e-9)
        ResidentShareMemory.reset()
        assertEquals(0.55, share(3), 1e-9)
        assertFalse(ResidentShareMemory.afterLoad("qwen", 1.0, 9_000 * mib, 1_000 * mib, 0))
    }
}
