package io.androllm.engine.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ModelResourceGuard.checkAgainst] — the pure decision logic behind
 * the pre-load RAM gate. All tests use explicit available-RAM values so no
 * Android framework is required; the production [ModelResourceGuard.check]
 * merely feeds it live `availMem`/low-memory state.
 */
class ModelResourceGuardTest {

    // A 1B-class Q4 model: ~700 MB file, 4k context, 28 layers, GQA 4/128.
    private val smallModelBytes = 700L * 1024 * 1024

    // An 8B-class Q4 model: ~4.9 GB file, 8k context, 32 layers, GQA 8/128.
    private val bigModelBytes = 4_900L * 1024 * 1024

    private val mb = 1024L * 1024

    @Test
    fun `available RAM does not subtract this process native heap a second time`() {
        // Step 4 on the phone: availMem ~3.5 GB, ~2.5 GB native heap (the
        // resident Qwen) → the old code reported "avail=0MB"... and refused Gemma 4.
        val avail = ModelResourceGuard().computeAvailableBytes(
            availMem = 3_500 * mb,
            processNativeHeapBytes = 2_500 * mb,
            replacingResidentModel = false
        )
        assertEquals(3_500 * mb, avail)
    }

    @Test
    fun `heap of a resident model being replaced counts as reclaimable`() {
        val avail = ModelResourceGuard().computeAvailableBytes(
            availMem = 1_000 * mb,
            processNativeHeapBytes = 2_000 * mb,
            replacingResidentModel = true
        )
        assertEquals(3_000 * mb, avail)
    }

    @Test
    fun `negative inputs are floored`() {
        assertEquals(0L, ModelResourceGuard().computeAvailableBytes(-1L, -5L, true))
    }

    @Test
    fun `small model fits on a 6 GB device`() {
        val result = ModelResourceGuard().checkAgainst(
            availableBytes = 3L * 1024 * 1024 * 1024, // 3 GB free
            lowMemory = false,
            fileSizeBytes = smallModelBytes,
            contextLength = 4096,
            blockCount = 28,
            headCountKv = 4,
            keyLength = 128
        )
        assertTrue("small model must fit: $result", result is ResourceCheck.Allowed)
    }

    @Test
    fun `big model is refused on a low-RAM device with a numeric reason`() {
        val result = ModelResourceGuard().checkAgainst(
            availableBytes = 3L * 1024 * 1024 * 1024,
            lowMemory = false,
            fileSizeBytes = bigModelBytes,
            contextLength = 8192,
            blockCount = 32,
            headCountKv = 8,
            keyLength = 128
        )
        assertTrue("8B model must be refused on 3 GB free", result is ResourceCheck.Insufficient)
        val insufficient = result as ResourceCheck.Insufficient
        assertTrue("reason must carry the numbers", insufficient.reason.contains("MB"))
        assertTrue(insufficient.neededBytes > insufficient.availableBytes)
    }

    @Test
    fun `low-memory state refuses even a small model`() {
        val result = ModelResourceGuard().checkAgainst(
            availableBytes = 4L * 1024 * 1024 * 1024,
            lowMemory = true,
            fileSizeBytes = smallModelBytes,
            contextLength = 4096,
            blockCount = 28,
            headCountKv = 4,
            keyLength = 128
        )
        assertTrue("low-memory flag must refuse any load", result is ResourceCheck.Insufficient)
        assertTrue((result as ResourceCheck.Insufficient).reason.contains("low"))
    }

    @Test
    fun `layer-accurate geometry makes the decision more conservative than the fallback`() {
        val guard = ModelResourceGuard()
        // 16k context on 2 GB free: the REAL GQA geometry (28 layers × 4 kv
        // heads × 128 dim) sizes the KV cache at ~940 MB → refused; the
        // size-aware fallback (~14 KB/token for a 700 MB file) estimates far
        // less → allowed. They must diverge — that divergence is the point of
        // parsing the header geometry.
        val withGeometry = guard.checkAgainst(
            availableBytes = 2L * 1024 * 1024 * 1024,
            lowMemory = false,
            fileSizeBytes = smallModelBytes,
            contextLength = 16384,
            blockCount = 28,
            headCountKv = 4,
            keyLength = 128
        )
        val withoutGeometry = guard.checkAgainst(
            availableBytes = 2L * 1024 * 1024 * 1024,
            lowMemory = false,
            fileSizeBytes = smallModelBytes,
            contextLength = 16384,
            blockCount = 0,
            headCountKv = 0,
            keyLength = 0
        )
        assertTrue("real geometry must refuse a 16k context on 2 GB free", withGeometry is ResourceCheck.Insufficient)
        assertTrue("size-aware fallback must be less conservative", withoutGeometry is ResourceCheck.Allowed)
    }

    @Test
    fun `estimate footprint is composed from weights kv and scratch`() {
        val guard = ModelResourceGuard()
        val footprint = guard.estimateFootprint(
            fileSizeBytes = smallModelBytes,
            contextLength = 4096,
            blockCount = 28,
            headCountKv = 4,
            keyLength = 128
        )
        val weights = MemoryEstimator.estimateWeightsMemory(smallModelBytes)
        assertTrue("footprint must exceed weights alone", footprint > weights)
    }

    // ── Branch U: step 5 Gemma 4 E2B on GPU refused without `am kill-all` ──

    private val mib = 1024L * 1024L
    private val gemma4E2bFile = 2_468L * mib // weights estimate 2591 MB, as logged on the phone

    @Test
    fun `step 5 numbers - whole file counted as resident is refused`() {
        val r = ModelResourceGuard().attemptAggressiveFit(
            fileSizeBytes = gemma4E2bFile, contextLength = 8192, availableBytes = 3_163L * mib,
            lowMemory = false, requestedBackend = io.androllm.engine.models.BackendType.GPU
        )
        assertTrue(r is AggressiveFitResult.NoFit)
    }

    @Test
    fun `gemma E2B with mmapped PLE tables fits the same free RAM on GPU`() {
        val fraction = MemoryEstimator.residentWeightFraction("Gemma 4 E2B IT LiteRT", "E2B")
        val r = ModelResourceGuard().attemptAggressiveFit(
            fileSizeBytes = gemma4E2bFile, contextLength = 8192, availableBytes = 3_163L * mib,
            lowMemory = false, requestedBackend = io.androllm.engine.models.BackendType.GPU,
            weightFraction = fraction
        )
        assertTrue(r is AggressiveFitResult.Fit)
        r as AggressiveFitResult.Fit
        assertEquals(io.androllm.engine.models.BackendType.GPU, r.effectiveBackend)
        assertTrue(r.effectiveContext >= 4096)
        // Still refuses when there is genuinely no room.
        val tight = ModelResourceGuard().attemptAggressiveFit(
            fileSizeBytes = gemma4E2bFile, contextLength = 8192, availableBytes = 1_200L * mib,
            lowMemory = false, requestedBackend = io.androllm.engine.models.BackendType.GPU,
            weightFraction = fraction
        )
        assertTrue(tight is AggressiveFitResult.NoFit)
    }

    @Test
    fun `resident fraction applies only to gemma effective models`() {
        assertEquals(0.55, MemoryEstimator.residentWeightFraction("Gemma 4 E2B IT LiteRT"), 1e-9)
        assertEquals(0.66, MemoryEstimator.residentWeightFraction("gemma-3n-E4B-it-int4"), 1e-9)
        assertEquals(1.0, MemoryEstimator.residentWeightFraction("Qwen3 0.6B"), 1e-9)
        assertEquals(1.0, MemoryEstimator.residentWeightFraction("Gemma 3 1B IT"), 1e-9)
        assertEquals(1.0, MemoryEstimator.residentWeightFraction(null), 1e-9)
    }
}
