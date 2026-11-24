package com.manc.voice

import com.manc.voice.audio.dsp.VoiceLockGate
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.random.Random

/**
 * Port of the offline tuning simulation: a turn-taking timeline of owner /
 * bystander / silence segments (seconds-scale at 100 fps), driving the gate with
 * simulated owner-similarity. Asserts the owner passes near unity while the
 * bystander is strongly attenuated. Mirrors the numbers in docs/ARCHITECTURE.md.
 */
class VoiceLockGateTest {
    @Test fun ownerPassesBystanderMuted() {
        val gate = VoiceLockGate(10.0).apply { strength = 0.5; enabled = true }
        val rng = Random(7)
        // (frames, state): 0 silence, 1 owner, 2 bystander — hundreds of frames = seconds
        val script = listOf(120 to 0, 250 to 1, 180 to 2, 220 to 1, 150 to 2, 330 to 1, 200 to 2, 240 to 1)
        var ownerSum = 0.0; var ownerN = 0
        var bystSum = 0.0; var bystN = 0
        repeat(3) {
            for ((dur, st) in script) {
                repeat(dur) {
                    val (vad, sim) = when (st) {
                        1 -> 0.95 to (0.82 + rng.nextDouble(-0.08, 0.08))
                        2 -> 0.95 to (0.38 + rng.nextDouble(-0.10, 0.10))
                        else -> 0.25 to (0.20 + rng.nextDouble(-0.10, 0.10))
                    }
                    val g = gate.process(vad, sim.coerceIn(0.0, 1.0), 6.0)
                    when (st) { 1 -> { ownerSum += g; ownerN++ }; 2 -> { bystSum += g; bystN++ } }
                }
            }
        }
        val ownerMean = ownerSum / ownerN
        val bystMean = bystSum / bystN
        val bystDb = 20 * log10(maxOf(bystMean, 1e-4))
        println("ownerMean=$ownerMean bystMean=$bystMean bystDb=$bystDb")
        assertTrue("owner should pass (>0.9) but was $ownerMean", ownerMean > 0.9)
        assertTrue("bystander should be attenuated >=10 dB but was $bystDb dB", bystDb < -10.0)
    }
}
