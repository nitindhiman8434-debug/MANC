package com.manc.voice.audio.dsp

import kotlin.math.max
import kotlin.math.min

/**
 * Per-bin noise power tracker using minimum-statistics style tracking:
 * fast follow downward, slow leak upward, with a separate speech-presence
 * smoother so we do not learn the user's voice as noise.
 */
class NoiseEstimator(bins: Int) {
    private val noise = DoubleArray(bins) { 1e-6 }
    private val minTrack = DoubleArray(bins) { 1e-6 }
    private var frames = 0

    fun update(power: DoubleArray, speechProb: Double) {
        frames++
        val learn = (1.0 - speechProb).coerceIn(0.0, 1.0)
        for (k in power.indices) {
            val p = power[k]
            // minimum tracking
            minTrack[k] = if (p < minTrack[k]) p else minTrack[k] * 1.0003 + p * 0.0005
            // only adapt noise estimate when speech is unlikely
            val a = 0.02 + 0.08 * learn
            val target = max(minTrack[k], if (learn > 0.5) p else noise[k])
            noise[k] = (1 - a) * noise[k] + a * target
            noise[k] = max(noise[k], 1e-8)
        }
    }

    fun noisePower(): DoubleArray = noise
}
