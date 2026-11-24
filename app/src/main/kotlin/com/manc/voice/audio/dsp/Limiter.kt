package com.manc.voice.audio.dsp

import kotlin.math.abs

/** Make-up gain + soft limiter so the cleaned voice is loud but never clips. */
class Limiter {
    var makeupGain = 1.6
    private var env = 0.0
    private val threshold = 0.92

    fun process(x: FloatArray, n: Int) {
        for (i in 0 until n) {
            var s: Double = x[i] * makeupGain
            val a = abs(s)
            val coeff = if (a > env) 0.4 else 0.05
            env += coeff * (a - env)
            if (env > threshold) {
                s *= (threshold / env)
            }
            if (s > 1.0) s = 1.0 else if (s < -1.0) s = -1.0
            x[i] = s.toFloat()
        }
    }
}
