package com.manc.voice.audio.dsp

import kotlin.math.max
import kotlin.math.min

/**
 * Per-bin noise power tracker combining two mechanisms:
 *  1. Minimum statistics: the floor of smoothed power over a ~1.3 s window of
 *     sub-window minima (speech has valleys; background does not). This learns
 *     noise even when the VAD is fooled, which breaks the "VAD high -> never learn
 *     -> VAD stays high" deadlock, and never learns sustained speech as noise.
 *  2. VAD-gated fast adaptation when speech is clearly absent.
 * A freeze flag suspends adaptation entirely (used during voice enrollment).
 */
class NoiseEstimator(private val bins: Int) {
    private val noise = DoubleArray(bins) { 1e-6 }
    private val smooth = DoubleArray(bins) { 1e-6 }
    private val subMin = DoubleArray(bins) { Double.MAX_VALUE }
    private val U = 8                     // sub-windows kept
    private val D = 20                    // frames per sub-window (~160 ms at 8 ms hop)
    private val ring = Array(U) { DoubleArray(bins) { Double.MAX_VALUE } }
    private var ringPos = 0
    private var frameInSub = 0
    private val bias = 1.8                // min-stats underestimates mean noise power
    @Volatile var freeze = false

    fun update(power: DoubleArray, speechProb: Double) {
        if (freeze) return
        val absent = speechProb < 0.35
        for (k in 0 until bins) {
            smooth[k] = 0.7 * smooth[k] + 0.3 * power[k]
            if (smooth[k] < subMin[k]) subMin[k] = smooth[k]
        }
        frameInSub++
        if (frameInSub >= D) {
            System.arraycopy(subMin, 0, ring[ringPos], 0, bins)
            java.util.Arrays.fill(subMin, Double.MAX_VALUE)
            ringPos = (ringPos + 1) % U
            frameInSub = 0
        }
        for (k in 0 until bins) {
            var floor = subMin[k]
            for (u in 0 until U) floor = min(floor, ring[u][k])
            val floorNoise = if (floor == Double.MAX_VALUE) noise[k] else floor * bias
            if (absent) {
                val a = if (smooth[k] > noise[k]) 0.05 else 0.3   // slow rise, fast fall
                noise[k] = (1 - a) * noise[k] + a * smooth[k]
            } else {
                noise[k] = 0.95 * noise[k] + 0.05 * floorNoise    // follow the min-stats floor
            }
            noise[k] = max(noise[k], 1e-8)
        }
    }

    fun noisePower(): DoubleArray = noise
}
