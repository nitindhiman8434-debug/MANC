package com.manc.voice

import com.manc.voice.audio.dsp.NoiseEstimator
import com.manc.voice.audio.dsp.SpectralDenoiser
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectralDenoiserTest {
    @Test fun attenuatesNoiseBins() {
        val bins = 129
        val den = SpectralDenoiser(bins).apply { strength = 0.7 }
        val noise = DoubleArray(bins) { 1.0 }
        val est = NoiseEstimator(bins)
        // learn noise for a while (no speech)
        repeat(50) { est.update(DoubleArray(bins) { 1.0 }, 0.0) }
        // a frame that is mostly noise with one strong speech bin
        val power = DoubleArray(bins) { 1.0 }
        power[40] = 50.0
        val g = den.computeGain(power, est.noisePower())
        assertTrue("noise bin attenuated", g[10] < 0.5)
        assertTrue("speech bin preserved", g[40] > 0.8)
    }
}
