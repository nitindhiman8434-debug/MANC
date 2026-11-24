package com.manc.voice.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Square-root Hann window for overlap-add analysis/synthesis with 50% overlap. */
object Window {
    fun sqrtHann(n: Int): DoubleArray {
        val w = DoubleArray(n)
        for (i in 0 until n) {
            val h = 0.5 * (1.0 - cos(2.0 * PI * i / n))
            w[i] = sqrt(h)
        }
        return w
    }
}
