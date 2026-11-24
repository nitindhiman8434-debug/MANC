package com.manc.voice.audio.dsp

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure-Kotlin spectral suppressor (fallback denoiser when no neural model is
 * loaded). Decision-directed a-priori SNR (Ephraim–Malah style) with a Wiener
 * gain, over-subtraction for deeper noise removal, a low spectral floor, and
 * temporal gain smoothing to suppress musical noise.
 *
 * strength in [0,1] scales how aggressive the suppression is.
 */
class SpectralDenoiser(private val bins: Int) {
    private val prevGain = DoubleArray(bins) { 1.0 }
    private val prevClean = DoubleArray(bins)
    var strength = 0.85

    val gain = DoubleArray(bins) { 1.0 }

    fun computeGain(power: DoubleArray, noise: DoubleArray): DoubleArray {
        val alpha = 0.98
        val floor = lerp(0.12, 0.004, strength)       // min linear gain (deep when strong)
        val beta = lerp(1.0, 2.2, strength)            // over-subtraction factor
        val smooth = lerp(0.3, 0.6, strength)          // temporal gain smoothing
        for (k in 0 until bins) {
            val n = max(noise[k], 1e-9) * beta
            val postSnr = max(power[k] / n - 1.0, 0.0)
            val prior = alpha * (prevClean[k] / n) + (1 - alpha) * postSnr
            var g = prior / (prior + 1.0)              // Wiener gain
            // temporal smoothing reduces isolated musical tones
            g = smooth * prevGain[k] + (1 - smooth) * g
            val gg = max(g, floor)
            prevGain[k] = gg
            gain[k] = gg
            val mag = sqrt(max(power[k], 0.0))
            prevClean[k] = (gg * mag) * (gg * mag)
        }
        // (no cross-bin smoothing: it blurred voice harmonics into the noise floor)
        return gain
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t.coerceIn(0.0, 1.0)
}
