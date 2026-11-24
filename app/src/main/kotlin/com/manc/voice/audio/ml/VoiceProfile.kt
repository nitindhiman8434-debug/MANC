package com.manc.voice.audio.ml

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** A learned owner reference and a per-frame similarity scorer in [0,1]. */
interface VoiceProfile {
    val isEnrolled: Boolean
    fun beginEnroll()
    fun addEnrollFrame(shape: DoubleArray, f0: Double, voiced: Boolean)
    fun finishEnroll(): Boolean
    /** Voiced frames collected in the current enrollment (progress, and what finishEnroll judges). */
    val enrollVoicedFrames: Int
    fun similarity(shape: DoubleArray, f0: Double, voiced: Boolean): Double
    fun exportState(): String
    fun importState(s: String): Boolean
}

/** Model-free profile: mean log-spectrum shape + f0 range. */
class HeuristicVoiceProfile(private val bands: Int = 16) : VoiceProfile {
    companion object {
        /** 150 frames of 8 ms: about 1.2 s of voiced sound. */
        const val MIN_ENROLL_VOICED_FRAMES = 150
        const val FRAME_MS = 8
    }

    private val refShape = DoubleArray(bands)
    private var refF0 = 0.0
    private var f0Spread = 40.0
    override var isEnrolled = false
        private set

    private val accShape = DoubleArray(bands)
    private var accCount = 0
    override val enrollVoicedFrames: Int get() = accCount
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
        // A voiceprint needs real speech: about 1.2 s of voiced sound out of the 5 s
        // recording. The old limit (a quarter of a second) accepted a cough or a TV.
        if (accCount < MIN_ENROLL_VOICED_FRAMES) return false
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

    private var emaSim = 1.0
    private var lastRaw = 0.8

    override fun similarity(shape: DoubleArray, f0: Double, voiced: Boolean): Double {
        if (!isEnrolled) return 1.0 // not locked yet -> pass everything
        // cosine of normalized shapes (both unit-norm already). The map accepts the
        // owner reliably (dot ~0.6-1.0) while pushing a different voice (dot <~0.5) low.
        var dot = 0.0
        for (b in 0 until bands) dot += shape[b] * refShape[b]
        val timbre = ((dot - 0.35) / 0.55).coerceIn(0.0, 1.0)
        val raw: Double = if (voiced && f0 > 0 && refF0 > 0) {
            // Octave-tolerant pitch match: pitch trackers make octave errors in noise,
            // so accept f0, 2*f0 and f0/2 against the reference with a mild penalty.
            fun match(ref: Double, pen: Double): Double {
                val d = abs(f0 - ref) / f0Spread
                return pen * exp(-0.5 * d * d)
            }
            val f0Score = maxOf(match(refF0, 1.0), match(refF0 * 2.0, 0.85), match(refF0 / 2.0, 0.85))
            lastRaw = (0.5 * timbre + 0.5 * f0Score).coerceIn(0.0, 1.0)
            lastRaw
        } else {
            // unvoiced frame (consonant / gap / pitch not found): hold the recent
            // decision so the gate does not chop the owner mid-word; drift only slowly
            // toward the timbre evidence.
            // Drift toward a neutral score (keeps an open gate open, does not open a closed one).
            lastRaw = (0.95 * lastRaw + 0.05 * max(timbre, 0.46)).coerceIn(0.0, 1.0)
            lastRaw
        }
        // temporal smoothing so a single bad frame never flips the gate
        emaSim = 0.6 * emaSim + 0.4 * raw
        return emaSim
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
