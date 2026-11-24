package com.manc.voice.audio.dsp

import kotlin.math.ln
import kotlin.math.max

/**
 * Lightweight voice-activity detector: a-posteriori SNR over the speech band
 * plus spectral-flatness (noise is flat, voiced speech is peaky), with an
 * absolute energy floor. Rises fast, falls slow. Output in [0,1].
 */
class Vad(private val sampleRate: Int, private val bins: Int, private val frameSize: Int) {
    private var prob = 0.0
    private val loBin = (300.0 * frameSize / sampleRate).toInt().coerceAtLeast(1)
    private val hiBin = (3400.0 * frameSize / sampleRate).toInt().coerceAtMost(bins - 1)

    fun process(power: DoubleArray, noise: DoubleArray): Double {
        var snrAccum = 0.0
        var geo = 0.0
        var arith = 0.0
        var count = 0
        for (k in loBin..hiBin) {
            val n = max(noise[k], 1e-8)
            snrAccum += power[k] / n
            val p = max(power[k], 1e-10)
            geo += ln(p)
            arith += p
            count++
        }
        val meanSnr = snrAccum / count
        geo = Math.exp(geo / count)
        arith /= count
        if (arith < 1e-7) {                       // essentially silent frame
            prob *= 0.7
            return prob
        }
        val flatness = (geo / max(arith, 1e-10)).coerceIn(0.0, 1.0)
        val snrScore = (ln(max(meanSnr, 1e-3)) / ln(6.0)).coerceIn(0.0, 1.0)
        val tonal = (1.0 - flatness)
        val instant = (0.5 * snrScore + 0.5 * tonal).coerceIn(0.0, 1.0)
        val a = if (instant > prob) 0.7 else 0.12
        prob += a * (instant - prob)
        return prob
    }
}
