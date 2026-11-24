package com.manc.voice.audio.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * Iterative in-place radix-2 Cooley–Tukey FFT. Sizes must be powers of two.
 * Real-input helpers pack/unpack for spectral processing at our frame sizes (256/512).
 */
class Fft(val n: Int) {
    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two: $n" }
    }

    private val cosT = DoubleArray(n / 2)
    private val sinT = DoubleArray(n / 2)
    private val rev = IntArray(n)

    init {
        for (i in 0 until n / 2) {
            val a = -2.0 * Math.PI * i / n
            cosT[i] = cos(a)
            sinT[i] = sin(a)
        }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            rev[i] = j
        }
    }

    /** In-place complex FFT. re/im length n. inverse=false forward. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                var idx = 0
                while (k < half) {
                    val cosv = cosT[idx]
                    val sinv = if (inverse) -sinT[idx] else sinT[idx]
                    val a = i + k
                    val b = a + half
                    val tre = re[b] * cosv - im[b] * sinv
                    val tim = re[b] * sinv + im[b] * cosv
                    re[b] = re[a] - tre
                    im[b] = im[a] - tim
                    re[a] += tre
                    im[a] += tim
                    k++; idx += step
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            val inv = 1.0 / n
            for (i in 0 until n) { re[i] *= inv; im[i] *= inv }
        }
    }
}
