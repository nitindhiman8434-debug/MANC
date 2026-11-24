package com.manc.voice.audio.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Automatic gain control + soft limiter: while the owner is speaking, the level
 * is slowly driven toward a target so the caller hears a consistently loud,
 * clear voice; the limiter guarantees it never clips.
 */
class Limiter {
    var makeupGain = 1.7
    var agcEnabled = true
    var targetRms = 0.12          // about -18 dBFS
    private var agcGain = 1.0
    private var env = 0.0
    private val threshold = 0.92

    fun process(x: FloatArray, n: Int, speechActive: Boolean = true) {
        if (agcEnabled && speechActive && n > 0) {
            var s = 0.0
            for (i in 0 until n) s += x[i] * x[i]
            val r = sqrt(s / n)
            if (r > 0.004) {
                val desired = (targetRms / (r * makeupGain)).coerceIn(0.5, 8.0)
                agcGain += 0.06 * (desired - agcGain)   // slow, no pumping
            }
        }
        val g = makeupGain * (if (agcEnabled) agcGain else 1.0)
        for (i in 0 until n) {
            var v: Double = x[i] * g
            val a = abs(v)
            val coeff = if (a > env) 0.4 else 0.05
            env += coeff * (a - env)
            if (env > threshold) v *= (threshold / env)
            if (v > 1.0) v = 1.0 else if (v < -1.0) v = -1.0
            x[i] = v.toFloat()
        }
    }
}
