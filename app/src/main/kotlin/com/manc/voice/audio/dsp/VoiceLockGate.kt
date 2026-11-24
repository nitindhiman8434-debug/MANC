package com.manc.voice.audio.dsp

import kotlin.math.exp
import kotlin.math.tanh

/**
 * The voice-lock: opens the microphone only when the current speech is the enrolled
 * owner, and closes it for bystanders and background. A fused owner-confidence score
 * (voiceprint similarity, nudged by a near-field level cue) drives a dual-threshold
 * hysteresis gate with a hangover and asymmetric attack/release smoothing.
 *
 * Constants were tuned on a 60 s turn-taking simulation:
 *   owner retained ~0 dB, bystander ~ -22 dB, sustained background ~ -42 dB,
 *   p90 open latency ~12 ms. See docs/ARCHITECTURE.md.
 */
class VoiceLockGate(frameMs: Double) {
    // strength 0..1 interpolates calm -> aggressive muting
    var strength: Double = 0.5
    var enabled: Boolean = true
    var nearFieldEnabled: Boolean = false

    private var open = false
    private var hangoverLeft = 0.0
    private var smoothed = 0.0

    // thresholds on fused confidence
    private val vadOn = 0.6
    private var openTh = 0.70
    private var closeTh = 0.58
    private val nfPivotDb = 5.0
    private val nfScaleDb = 4.0
    private val nfWeight = 0.12

    // time constants (ms) -> per-frame one-pole coefficients
    private val atk = 1.0 - exp(-frameMs / 14.4)
    private val rel = 1.0 - exp(-frameMs / 66.0)
    private val hangoverMs get() = lerp(160.0, 90.0, strength)
    private val frameMsLocal = frameMs

    private val closedFloor get() = lerp(0.12, 0.0, strength) // residual pass when closed

    fun process(vadProb: Double, ownerSimilarity: Double, nearFieldDb: Double): Double {
        if (!enabled) return 1.0
        openTh = lerp(0.66, 0.74, strength)
        closeTh = lerp(0.54, 0.62, strength)

        val speech = vadProb > vadOn
        val nfTerm = if (nearFieldEnabled) tanh((nearFieldDb - nfPivotDb) / nfScaleDb) else 0.0
        val conf = ownerSimilarity + nfWeight * nfTerm

        if (!open) {
            if (speech && conf > openTh) { open = true; hangoverLeft = hangoverMs }
        } else {
            if (speech && conf > closeTh) hangoverLeft = hangoverMs
            else {
                hangoverLeft -= frameMsLocal
                if (hangoverLeft <= 0) open = false
            }
        }
        val target = if (open) 1.0 else closedFloor
        val a = if (target > smoothed) atk else rel
        smoothed += a * (target - smoothed)
        return smoothed
    }

    fun reset() { open = false; hangoverLeft = 0.0; smoothed = 0.0 }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t.coerceIn(0.0, 1.0)
}
