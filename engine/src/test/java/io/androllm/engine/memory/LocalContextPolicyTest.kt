package io.androllm.engine.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalContextPolicyTest {

    @Test
    fun twelveGbPhoneGetsFullCatalogContext() {
        // S24 Ultra reports ~11 GB total; an 8K catalog model is not cut to 4096.
        assertEquals(8192, LocalContextPolicy.resolve(8192, 11.1f))
    }

    @Test
    fun largeCatalogContextIsCappedByRam() {
        assertEquals(16384, LocalContextPolicy.resolve(32768, 11.1f))
        assertEquals(8192, LocalContextPolicy.resolve(32768, 7.3f))
        assertEquals(4096, LocalContextPolicy.resolve(32768, 5.5f))
        assertEquals(2048, LocalContextPolicy.resolve(32768, 3.0f))
    }

    @Test
    fun smallCatalogContextIsKept() {
        assertEquals(2048, LocalContextPolicy.resolve(2048, 11.1f))
    }

    @Test
    fun unknownValuesFallBack() {
        assertEquals(LocalContextPolicy.FALLBACK_CONTEXT, LocalContextPolicy.resolve(0, 11.1f))
        assertEquals(LocalContextPolicy.FALLBACK_CONTEXT, LocalContextPolicy.resolve(8192, 0f))
    }

    @Test
    fun neverBelowMinimum() {
        assertEquals(LocalContextPolicy.MIN_CONTEXT, LocalContextPolicy.resolve(256, 11.1f))
    }
}
