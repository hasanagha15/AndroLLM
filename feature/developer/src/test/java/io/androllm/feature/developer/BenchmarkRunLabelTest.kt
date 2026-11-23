package io.androllm.feature.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkRunLabelTest {

    @Test
    fun runBeforeAnyResult() {
        assertEquals("Run", benchmarkRunLabel(null, isBenchmarking = false))
    }

    @Test
    fun runAgainAfterEmptyNoModelResult() {
        assertEquals("Run again", benchmarkRunLabel(emptyList(), isBenchmarking = false))
    }

    @Test
    fun noButtonWhileBenchmarking() {
        assertNull(benchmarkRunLabel(null, isBenchmarking = true))
        assertNull(benchmarkRunLabel(emptyList(), isBenchmarking = true))
    }
}
