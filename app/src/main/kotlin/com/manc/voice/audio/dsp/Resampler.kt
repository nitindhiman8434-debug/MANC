package com.manc.voice.audio.dsp

/**
 * Simple band-limited-ish linear resampler for converting between the device's
 * native capture rate and the 16 kHz engine rate. Good enough for voice; the
 * heavy lifting is done at 16 kHz.
 */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private var frac = 0.0
    private var last = 0f
    private val ratio = inRate.toDouble() / outRate.toDouble()

    fun process(input: FloatArray, inLen: Int, out: FloatArray): Int {
        if (inRate == outRate) {
            System.arraycopy(input, 0, out, 0, inLen)
            return inLen
        }
        var o = 0
        var pos = frac
        while (pos < inLen) {
            val i = pos.toInt()
            val f = pos - i
            val a = if (i == 0) last else input[i - 1]
            val b = input[i]
            if (o < out.size) out[o++] = (a + (b - a) * f).toFloat()
            pos += ratio
        }
        frac = pos - inLen
        last = input[inLen - 1]
        return o
    }
}
