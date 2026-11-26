package io.androllm.engine.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Branch U it.2 (gate Z7): the reduced resident share corrects itself. */
class ResidentShareMemoryTest {

    private val marks = mutableSetOf<String>()
    private val mib = 1024L * 1024L

    @Before
    fun setUp() {
        ResidentShareMemory.resetForTest(object : ResidentShareMemory.Store {
            override fun isMarked(key: String) = key in marks
            override fun mark(key: String) { marks += key }
        })
    }

    @Test
    fun `pss within tolerance keeps the reduced share`() {
        assertFalse(ResidentShareMemory.afterLoad("e2b", 0.55, 2_000 * mib, 1_800 * mib, 0))
        assertEquals(0.55, ResidentShareMemory.effectiveFraction("e2b", 0.55), 1e-9)
    }

    @Test
    fun `pss above estimate falls back to the full share`() {
        assertTrue(ResidentShareMemory.afterLoad("e2b", 0.55, 2_400 * mib, 1_800 * mib, 0))
        assertEquals(1.0, ResidentShareMemory.effectiveFraction("e2b", 0.55), 1e-9)
        // Other models are unaffected.
        assertEquals(0.66, ResidentShareMemory.effectiveFraction("e4b", 0.66), 1e-9)
    }

    @Test
    fun `memory pressure soon after a reduced load marks the model, later pressure does not`() {
        ResidentShareMemory.afterLoad("e2b", 0.55, 0, 0, nowMs = 1_000)
        assertFalse(ResidentShareMemory.onMemoryPressure(1_000 + ResidentShareMemory.PRESSURE_WINDOW_MS + 1))
        assertTrue(ResidentShareMemory.onMemoryPressure(1_000 + 60_000))
        assertEquals(1.0, ResidentShareMemory.effectiveFraction("e2b", 0.55), 1e-9)
    }

    @Test
    fun `oom while loading with a reduced share marks the model, full-share models are never marked`() {
        ResidentShareMemory.onLoadOom("qwen", 1.0)
        assertTrue(marks.isEmpty())
        ResidentShareMemory.onLoadOom("e2b", 0.55)
        assertEquals(1.0, ResidentShareMemory.effectiveFraction("e2b", 0.55), 1e-9)
        assertFalse(ResidentShareMemory.afterLoad("qwen", 1.0, 9_000 * mib, 1_000 * mib, 0))
    }
}
