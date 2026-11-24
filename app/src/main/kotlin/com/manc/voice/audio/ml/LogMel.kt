package com.manc.voice.audio.ml

import com.manc.voice.audio.dsp.Fft
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** 80-bin log-mel fbank (25 ms / 10 ms) feeding the optional ONNX speaker embedder. */
class LogMel(private val sampleRate: Int = 16000, val mels: Int = 80) {
    private val winLen = (0.025 * sampleRate).toInt()   // 400 @16k
    private val hop = (0.010 * sampleRate).toInt()       // 160 @16k
    private val fftSize = 512
    private val fft = Fft(fftSize)
    private val bins = fftSize / 2 + 1
    private val window = DoubleArray(winLen) { 0.54 - 0.46 * Math.cos(2.0 * Math.PI * it / (winLen - 1)) }
    private val filters = Array(mels) { DoubleArray(bins) }

    init {
        val fMax = sampleRate / 2.0
        fun hz2mel(f: Double) = 2595.0 * Math.log10(1.0 + f / 700.0)
        fun mel2hz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)
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

    /** Returns [frames][mels] log-mel of a mono signal. */
    fun extract(x: FloatArray): Array<DoubleArray> {
        val re = DoubleArray(fftSize); val im = DoubleArray(fftSize)
        val nFrames = max(0, 1 + (x.size - winLen) / hop)
        val out = Array(nFrames) { DoubleArray(mels) }
        for (t in 0 until nFrames) {
            val start = t * hop
            for (i in 0 until fftSize) {
                re[i] = if (i < winLen && start + i < x.size) x[start + i] * window[i] else 0.0
                im[i] = 0.0
            }
            fft.transform(re, im, false)
            val power = DoubleArray(bins)
            for (k in 0 until bins) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until mels) {
                var e = 0.0
                val f = filters[m]
                for (k in 0 until bins) e += f[k] * power[k]
                out[t][m] = ln(max(e, 1e-10))
            }
        }
        return out
    }
}
