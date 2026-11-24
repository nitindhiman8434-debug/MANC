package com.manc.voice.audio.ml

import com.manc.voice.audio.dsp.Fft
import kotlin.math.ln
import kotlin.math.max

/**
 * Kaldi-style 80-bin log-mel fbank matching what wespeaker / 3D-Speaker models
 * were trained on: 25 ms frames, 10 ms shift, DC removal, pre-emphasis 0.97,
 * Povey window, 512-pt FFT, mel 20 Hz..Nyquist, natural log of energies.
 * Samples are scaled to the int16 range (the bundled model declares
 * normalize_samples=0, i.e. it expects un-normalised 16-bit scale input).
 */
class LogMel(private val sampleRate: Int = 16000, val mels: Int = 80) {
    private val winLen = (0.025 * sampleRate).toInt()   // 400 @16k
    private val hop = (0.010 * sampleRate).toInt()       // 160 @16k
    private val fftSize = 512
    private val fft = Fft(fftSize)
    private val bins = fftSize / 2 + 1
    // Povey window: hann^0.85
    private val window = DoubleArray(winLen) {
        Math.pow(0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / (winLen - 1)), 0.85)
    }
    private val filters = Array(mels) { DoubleArray(bins) }

    init {
        val fMax = sampleRate / 2.0
        fun hz2mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
        fun mel2hz(m: Double) = 700.0 * (Math.exp(m / 1127.0) - 1.0)
        val mLo = hz2mel(20.0); val mHi = hz2mel(fMax)
        val pts = DoubleArray(mels + 2) { mel2hz(mLo + (mHi - mLo) * it / (mels + 1)) }
        val binHz = DoubleArray(mels + 2) { (pts[it] * fftSize / sampleRate) }
        for (m in 1..mels) {
            val l = binHz[m - 1]; val c = binHz[m]; val r = binHz[m + 1]
            for (k in 0 until bins) {
                val kf = k.toDouble()
                val w = when {
                    kf in l..c && c > l -> (kf - l) / (c - l)
                    kf in c..r && r > c -> (r - kf) / (r - c)
                    else -> 0.0
                }
                filters[m - 1][k] = max(0.0, w)
            }
        }
    }

    /** Returns [frames][mels] log-mel of a mono float signal in [-1,1]. */
    fun extract(x: FloatArray): Array<DoubleArray> {
        val re = DoubleArray(fftSize); val im = DoubleArray(fftSize)
        val frame = DoubleArray(winLen)
        val nFrames = max(0, 1 + (x.size - winLen) / hop)
        val out = Array(nFrames) { DoubleArray(mels) }
        for (t in 0 until nFrames) {
            val start = t * hop
            // int16 scale + DC removal
            var mean = 0.0
            for (i in 0 until winLen) { frame[i] = x[start + i] * 32768.0; mean += frame[i] }
            mean /= winLen
            for (i in 0 until winLen) frame[i] -= mean
            // pre-emphasis 0.97
            for (i in winLen - 1 downTo 1) frame[i] -= 0.97 * frame[i - 1]
            frame[0] -= 0.97 * frame[0]
            for (i in 0 until fftSize) {
                re[i] = if (i < winLen) frame[i] * window[i] else 0.0
                im[i] = 0.0
            }
            fft.transform(re, im, false)
            for (m in 0 until mels) {
                var e = 0.0
                val f = filters[m]
                for (k in 0 until bins) if (f[k] != 0.0) e += f[k] * (re[k] * re[k] + im[k] * im[k])
                out[t][m] = ln(max(e, 1.1920929e-7))
            }
        }
        return out
    }
}
