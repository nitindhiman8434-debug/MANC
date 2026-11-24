package com.manc.voice.audio.ml

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Compact, model-free voice descriptor used by the fallback voice-lock:
 *   - fundamental frequency (f0) from time-domain autocorrelation
 *   - a 16-band log-spectrum shape (timbre / formant fingerprint)
 * Similar enough to separate the owner from a clearly different bystander;
 * the ONNX embedder replaces this when a model is bundled.
 */
class VoiceFeatures(private val sampleRate: Int, bins: Int, private val frameSize: Int) {
    val bands = 16
    private val bandLo = IntArray(bands)
    private val bandHi = IntArray(bands)

    init {
        // log-spaced bands from 80 Hz to 4000 Hz
        val fLo = 80.0; val fHi = 4000.0
        for (b in 0 until bands) {
            val f1 = fLo * Math.pow(fHi / fLo, b.toDouble() / bands)
            val f2 = fLo * Math.pow(fHi / fLo, (b + 1.0) / bands)
            bandLo[b] = (f1 * frameSize / sampleRate).toInt().coerceIn(1, bins - 1)
            bandHi[b] = (f2 * frameSize / sampleRate).toInt().coerceIn(bandLo[b] + 1, bins - 1)
        }
    }

    /** 16-band normalized log shape from a magnitude spectrum. */
    fun spectralShape(mag: DoubleArray, out: DoubleArray) {
        var sum = 0.0
        for (b in 0 until bands) {
            var e = 0.0
            for (k in bandLo[b]..bandHi[b]) e += mag[k] * mag[k]
            val v = ln(max(e, 1e-9))
            out[b] = v
            sum += v
        }
        val mean = sum / bands
        var norm = 0.0
        for (b in 0 until bands) { out[b] -= mean; norm += out[b] * out[b] }
        norm = sqrt(max(norm, 1e-9))
        for (b in 0 until bands) out[b] /= norm
    }

    /** f0 in Hz via normalized autocorrelation on a time-domain hop; 0 if unvoiced. */
    fun pitch(frame: FloatArray, n: Int): Double {
        val minLag = sampleRate / 400
        val maxLag = sampleRate / 70
        if (maxLag >= n) return 0.0
        var e0 = 1e-9
        for (i in 0 until n) e0 += frame[i] * frame[i]
        var bestLag = -1
        var best = 0.0
        var lag = minLag
        while (lag <= maxLag) {
            var ac = 0.0
            var i = lag
            while (i < n) { ac += frame[i] * frame[i - lag]; i++ }
            val norm = ac / e0
            if (norm > best) { best = norm; bestLag = lag }
            lag++
        }
        return if (best > 0.3 && bestLag > 0) sampleRate.toDouble() / bestLag else 0.0
    }
}
