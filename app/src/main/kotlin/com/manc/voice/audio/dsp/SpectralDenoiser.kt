package com.manc.voice.audio.dsp

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin spectral suppressor used as the always-available fallback denoiser.
 * Decision-directed a-priori SNR (Ephraim–Malah style) with a Wiener gain and a
 * configurable maximum attenuation so the result never sounds fully gated.
 *
 * strength in [0,1] scales how aggressive the floor is.
 */
class SpectralDenoiser(private val bins: Int) {
    private val prevGainMag = DoubleArray(bins) { 1.0 }
    private val prevClean = DoubleArray(bins)
    var strength = 0.6

    val gain = DoubleArray(bins) { 1.0 }

    fun computeGain(power: DoubleArray, noise: DoubleArray): DoubleArray {
        val alpha = 0.98
        val floor = lerp(0.18, 0.03, strength) // min linear gain
        for (k in 0 until bins) {
            val n = max(noise[k], 1e-9)
            val postSnr = max(power[k] / n - 1.0, 0.0)
            val prior = alpha * (prevClean[k] / n) + (1 - alpha) * postSnr
            val g = prior / (prior + 1.0)            // Wiener gain
            val gg = max(g, floor)
            gain[k] = gg
            val mag = sqrt(max(power[k], 0.0))
            prevClean[k] = (gg * mag) * (gg * mag)
        }
        return gain
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t.coerceIn(0.0, 1.0)
}
