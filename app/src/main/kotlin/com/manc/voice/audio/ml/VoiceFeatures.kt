package com.manc.voice.audio.ml

import com.manc.voice.audio.dsp.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
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
        var maxE = 0.0
        for (b in 0 until bands) {
            var e = 0.0
            for (k in bandLo[b]..bandHi[b]) e += mag[k] * mag[k]
            out[b] = e
            if (e > maxE) maxE = e
        }
        // floor empty bands at -40 dB relative to the loudest band so the shape is
        // not dominated by near-zero bins (which swing wildly after denoising)
        val floor = max(maxE * 1e-4, 1e-12)
        for (b in 0 until bands) {
            val v = ln(max(out[b], floor))
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
        return if (best > 0.22 && bestLag > 0) sampleRate.toDouble() / bestLag else 0.0
    }

    // ---- harmonic-comb pitch (noise-robust) ----
    private val pN = 1024
    private val pitchFft = Fft(pN)
    private val pre = DoubleArray(pN); private val pim = DoubleArray(pN)
    private val pmag = DoubleArray(pN / 2 + 1)
    private val pwin = DoubleArray(pN) { 0.5 - 0.5 * cos(2.0 * PI * it / pN) }
    /** Voiced when the best (comb - anti-comb) score exceeds this fraction of the band mean. */
    var combThreshold = 0.45
    /** Diagnostics: last computed voicing ratio. */
    var lastCombRatio = 0.0
        private set

    /**
     * f0 via harmonic sieve on a 1024-pt spectrum of the (denoised) frame:
     * score(f) = mean over harmonics of [ |X(h f)| - 0.8 |X((h+0.5) f)| ].
     * Rewarding harmonic positions while penalising the gaps between them makes
     * the sieve robust to noise AND immune to the classic octave / alias errors
     * (a wrong candidate lands harmonics in the true spectrum's valleys).
     * Returns 0 when unvoiced.
     */
    fun combPitch(x: FloatArray, n: Int): Double {
        val binHz = sampleRate.toDouble() / pN
        for (i in 0 until pN) { pre[i] = (if (i < n) x[i].toDouble() else 0.0) * pwin[i]; pim[i] = 0.0 }
        pitchFft.transform(pre, pim, false)
        val kLo = (70.0 / binHz).toInt(); val kHi = (3400.0 / binHz).toInt().coerceAtMost(pN / 2)
        var bandSum = 0.0; var bandN = 0
        for (k in 0..pN / 2) { pmag[k] = hypot(pre[k], pim[k]); if (k in kLo..kHi) { bandSum += pmag[k]; bandN++ } }
        val bandMean = if (bandN > 0) bandSum / bandN else 0.0
        if (bandMean < 1e-7) { lastCombRatio = 0.0; return 0.0 }
        // log-compress so weak harmonics count and a few loud formant peaks cannot
        // make an alias candidate win
        val ref = bandMean * 0.1 + 1e-12
        for (k in 0..pN / 2) pmag[k] = ln(1.0 + pmag[k] / ref)
        var lSum = 0.0
        for (k in kLo..kHi) lSum += pmag[k]
        val lMean = lSum / bandN
        fun magAt(freq: Double): Double {
            val k = (freq / binHz).roundToInt().coerceIn(1, pN / 2 - 1)
            return max(pmag[k], max(pmag[k - 1], pmag[k + 1]))
        }
        var bestF = 0.0; var best = -1e9
        var f = 70.0
        while (f <= 400.0) {
            var s = 0.0; var h = 1
            while (h * f <= 3400.0 && h <= 24) {
                s += magAt(h * f) - 0.8 * magAt((h + 0.5) * f)
                h++
            }
            val used = h - 1
            if (used >= 4) { val sc = s / used; if (sc > best) { best = sc; bestF = f } }
            f += 2.0
        }
        val ratio = best / max(lMean, 1e-9)
        lastCombRatio = ratio
        return if (ratio > combThreshold) bestF else 0.0
    }
}
