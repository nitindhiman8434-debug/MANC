package com.manc.voice.audio.ml

import android.content.Context
import android.util.Log
import com.manc.voice.audio.dsp.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Streaming GTCRN neural denoiser (ultra-light, real-time on CPU).
 *
 * Contract (verified from the published ONNX): inputs `mix` [1,257,1,2]
 * (real/imag of one 512-pt STFT frame), `conv_cache` [2,1,16,16,33],
 * `tra_cache` [2,3,1,1,16], `inter_cache` [2,1,33,16] (zero at start);
 * outputs `enh` [1,257,1,2] plus the three updated caches, fed back each frame.
 * STFT: n_fft 512, hop 256, sqrt-Hann (periodic), 16 kHz, overlap-add ISTFT.
 *
 * Streams arbitrary block sizes: process(in, n, out) always yields n samples,
 * delayed by one hop (16 ms) plus the window overlap.
 */
class OnnxDenoiser private constructor(private val s: OrtBridge.Session) {
    private val N = 512; private val H = 256; private val B = N / 2 + 1
    private val fft = Fft(N)
    private val win = DoubleArray(N) { sqrt(0.5 * (1.0 - cos(2.0 * PI * it / N))) }
    private val inBuf = DoubleArray(N)      // sliding analysis window
    private val ola = DoubleArray(N)        // overlap-add synthesis
    private val re = DoubleArray(N); private val im = DoubleArray(N)

    private var convCache = FloatArray(2 * 1 * 16 * 16 * 33)
    private var traCache = FloatArray(2 * 3 * 1 * 1 * 16)
    private var interCache = FloatArray(2 * 1 * 33 * 16)
    private val mix = FloatArray(B * 2)

    // streaming rings: pending input samples and produced output samples
    private val pendIn = FloatArray(H)
    private var pendN = 0
    private val outQ = FloatArray(8 * N)
    private var outHead = 0; private var outTail = 0
    private var primed = false
    @Volatile var healthy = true; private set

    fun process(input: FloatArray, n: Int, out: FloatArray): Int {
        var i = 0
        while (i < n) {
            val take = minOf(H - pendN, n - i)
            System.arraycopy(input, i, pendIn, pendN, take)
            pendN += take; i += take
            if (pendN == H) { step(); pendN = 0 }
        }
        // emit exactly n samples (zeros until the pipeline is primed)
        for (k in 0 until n) {
            out[k] = if (outHead != outTail) { val v = outQ[outHead]; outHead = (outHead + 1) % outQ.size; v } else 0f
        }
        return n
    }

    private fun step() {
        // slide window by one hop
        System.arraycopy(inBuf, H, inBuf, 0, N - H)
        for (k in 0 until H) inBuf[N - H + k] = pendIn[k].toDouble()
        for (k in 0 until N) { re[k] = inBuf[k] * win[k]; im[k] = 0.0 }
        fft.transform(re, im, false)
        for (k in 0 until B) { mix[k * 2] = re[k].toFloat(); mix[k * 2 + 1] = im[k].toFloat() }

        val outputs = if (healthy) OrtBridge.runMulti(
            s, mapOf(
                "mix" to (mix to longArrayOf(1, B.toLong(), 1, 2)),
                "conv_cache" to (convCache to longArrayOf(2, 1, 16, 16, 33)),
                "tra_cache" to (traCache to longArrayOf(2, 3, 1, 1, 16)),
                "inter_cache" to (interCache to longArrayOf(2, 1, 33, 16))
            )
        ) else null

        val enh = outputs?.get("enh")
        if (enh != null && enh.size == B * 2) {
            outputs["conv_cache_out"]?.let { if (it.size == convCache.size) convCache = it }
            outputs["tra_cache_out"]?.let { if (it.size == traCache.size) traCache = it }
            outputs["inter_cache_out"]?.let { if (it.size == interCache.size) interCache = it }
            for (k in 0 until B) { re[k] = enh[k * 2].toDouble(); im[k] = enh[k * 2 + 1].toDouble() }
        } else {
            // model failure: pass the frame through unchanged, mark unhealthy once
            if (healthy) { Log.w(TAG, "GTCRN inference failed; passing through"); healthy = false }
            for (k in 0 until B) { /* re/im already hold the mix spectrum */ }
        }
        // Hermitian symmetry + ISTFT + OLA
        for (k in 1 until B - 1) { re[N - k] = re[k]; im[N - k] = -im[k] }
        fft.transform(re, im, true)
        for (k in 0 until N) ola[k] += re[k] * win[k]
        // sqrt-hann analysis * sqrt-hann synthesis with 50% overlap sums to 1
        for (k in 0 until H) push(ola[k].toFloat())
        System.arraycopy(ola, H, ola, 0, N - H)
        for (k in N - H until N) ola[k] = 0.0
        primed = true
    }

    private fun push(v: Float) {
        val next = (outTail + 1) % outQ.size
        if (next == outHead) outHead = (outHead + 1) % outQ.size // drop oldest on overflow
        outQ[outTail] = v; outTail = next
    }

    fun close() = s.close()

    companion object {
        private const val TAG = "OnnxDenoiser"
        fun tryLoad(ctx: Context): OnnxDenoiser? {
            val f = ModelRegistry.denoiserModel(ctx) ?: return null
            val s = OrtBridge.open(f, threads = 1) ?: return null
            val names = s.inputNames
            if (!names.containsAll(listOf("mix", "conv_cache", "tra_cache", "inter_cache"))) {
                Log.w(TAG, "denoiser.onnx does not match the GTCRN contract: $names"); s.close(); return null
            }
            return OnnxDenoiser(s)
        }
    }
}
