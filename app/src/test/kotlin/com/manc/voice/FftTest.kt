package com.manc.voice

import com.manc.voice.audio.dsp.Fft
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class FftTest {
    @Test fun forwardInverseRoundTrips() {
        val n = 256
        val fft = Fft(n)
        val re = DoubleArray(n) { Random(1).nextDouble(-1.0, 1.0) }
        val im = DoubleArray(n)
        val re0 = re.copyOf(); val im0 = im.copyOf()
        fft.transform(re, im, false)
        fft.transform(re, im, true)
        var maxErr = 0.0
        for (i in 0 until n) maxErr = maxOf(maxErr, abs(re[i] - re0[i]), abs(im[i] - im0[i]))
        assertEquals(0.0, maxErr, 1e-9)
    }
}
