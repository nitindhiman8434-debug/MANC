package com.manc.voice

import com.manc.voice.audio.dsp.NoiseEstimator
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseEstimatorTest {
    @Test fun doesNotLearnSpeechAsNoise() {
        val bins = 129
        val est = NoiseEstimator(bins)
        // learn a quiet floor first
        repeat(100) { est.update(DoubleArray(bins) { 0.01 }, 0.0) }
        val floor = est.noisePower()[40]
        // now speech-like activity: loud bursts with short pauses (real speech has
        // valleys); the estimate must stay near the pause level, not the burst level
        repeat(12) {
            repeat(30) { est.update(DoubleArray(bins) { if (it == 40) 50.0 else 0.01 }, 0.95) }
            repeat(10) { est.update(DoubleArray(bins) { 0.01 }, 0.9) }
        }
        val after = est.noisePower()[40]
        assertTrue("noise estimate must track pauses, not speech (floor=$floor after=$after)", after < 5.0)
    }

    @Test fun learnsNoiseWhenSpeechAbsent() {
        val bins = 129
        val est = NoiseEstimator(bins)
        repeat(400) { est.update(DoubleArray(bins) { 2.0 }, 0.05) }
        assertTrue("estimate should approach the noise level", est.noisePower()[10] > 1.0)
    }

    @Test fun freezeStopsAdaptation() {
        val bins = 129
        val est = NoiseEstimator(bins)
        est.freeze = true
        repeat(200) { est.update(DoubleArray(bins) { 5.0 }, 0.0) }
        assertTrue("frozen estimator must not move", est.noisePower()[10] < 0.01)
    }
}
