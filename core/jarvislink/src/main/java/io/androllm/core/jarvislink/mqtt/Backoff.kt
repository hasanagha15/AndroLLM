package io.androllm.core.jarvislink.mqtt

import kotlin.random.Random

/**
 * Exponential reconnect backoff with jitter: 2s, 4s, 8s … capped at [maxMs].
 * [reset] after a successful connect. Auth failures use [authFailureDelayMs]
 * (slow, so a wrong password never hammers the broker).
 */
class Backoff(
    private val initialMs: Long = 2_000,
    private val maxMs: Long = 120_000,
    private val factor: Double = 2.0,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default
) {
    private var attempt = 0

    val attempts: Int get() = attempt

    fun nextDelayMs(): Long {
        val base = (initialMs * Math.pow(factor, attempt.toDouble())).coerceAtMost(maxMs.toDouble()).toLong()
        if (attempt < 30) attempt++
        val jitter = (base * jitterRatio).toLong()
        val delta = if (jitter > 0) random.nextLong(-jitter, jitter + 1) else 0L
        return (base + delta).coerceIn(initialMs / 2, maxMs)
    }

    fun authFailureDelayMs(): Long = maxMs.also { if (attempt < 30) attempt++ }

    fun reset() { attempt = 0 }
}
