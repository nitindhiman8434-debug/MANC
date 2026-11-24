package com.manc.voice.audio.ml

import kotlin.math.abs
import kotlin.math.exp

/** A learned owner reference and a per-frame similarity scorer in [0,1]. */
interface VoiceProfile {
    val isEnrolled: Boolean
    fun beginEnroll()
    fun addEnrollFrame(shape: DoubleArray, f0: Double, voiced: Boolean)
    fun finishEnroll(): Boolean
    fun similarity(shape: DoubleArray, f0: Double, voiced: Boolean): Double
    fun exportState(): String
    fun importState(s: String): Boolean
}

/** Model-free profile: mean log-spectrum shape + f0 range. */
class HeuristicVoiceProfile(private val bands: Int = 16) : VoiceProfile {
    private val refShape = DoubleArray(bands)
    private var refF0 = 0.0
    private var f0Spread = 40.0
    override var isEnrolled = false
        private set

    private val accShape = DoubleArray(bands)
    private var accCount = 0
    private val f0s = ArrayList<Double>()

    override fun beginEnroll() {
        accShape.fill(0.0); accCount = 0; f0s.clear()
    }

    override fun addEnrollFrame(shape: DoubleArray, f0: Double, voiced: Boolean) {
        if (!voiced) return
        for (b in 0 until bands) accShape[b] += shape[b]
        accCount++
        if (f0 > 0) f0s.add(f0)
    }

    override fun finishEnroll(): Boolean {
        if (accCount < 30) return false
        for (b in 0 until bands) refShape[b] = accShape[b] / accCount
        if (f0s.isNotEmpty()) {
            f0s.sort()
            refF0 = f0s[f0s.size / 2]
            val lo = f0s[(f0s.size * 0.1).toInt()]
            val hi = f0s[(f0s.size * 0.9).toInt().coerceAtMost(f0s.size - 1)]
            f0Spread = ((hi - lo) / 2).coerceAtLeast(18.0)
        }
        isEnrolled = true
        return true
    }

    override fun similarity(shape: DoubleArray, f0: Double, voiced: Boolean): Double {
        if (!isEnrolled) return 1.0 // not locked yet -> pass everything
        // cosine of normalized shapes (both unit-norm already), sharpened so only a
        // genuinely close timbre scores high (broadly-similar voices are pushed down).
        var dot = 0.0
        for (b in 0 until bands) dot += shape[b] * refShape[b]
        val timbre = ((dot - 0.6) / 0.4).coerceIn(0.0, 1.0)
        if (voiced && f0 > 0 && refF0 > 0) {
            val d = abs(f0 - refF0) / f0Spread
            val f0Score = exp(-0.5 * d * d)
            // pitch is a strong speaker cue; weight it heavily when voiced
            return (0.45 * timbre + 0.55 * f0Score).coerceIn(0.0, 1.0)
        }
        // unvoiced frame: fall back to timbre alone
        return (0.9 * timbre).coerceIn(0.0, 1.0)
    }

    override fun exportState(): String {
        val sb = StringBuilder()
        sb.append(if (isEnrolled) 1 else 0).append(';')
        sb.append(refF0).append(';').append(f0Spread).append(';')
        sb.append(refShape.joinToString(","))
        return sb.toString()
    }

    override fun importState(s: String): Boolean {
        return try {
            val parts = s.split(';')
            isEnrolled = parts[0] == "1"
            refF0 = parts[1].toDouble()
            f0Spread = parts[2].toDouble()
            val arr = parts[3].split(',').map { it.toDouble() }
            if (arr.size == bands) for (b in 0 until bands) refShape[b] = arr[b]
            isEnrolled
        } catch (e: Exception) { false }
    }
}
