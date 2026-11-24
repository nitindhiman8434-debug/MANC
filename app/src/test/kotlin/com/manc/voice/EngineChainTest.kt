package com.manc.voice

import com.manc.voice.audio.dsp.*
import com.manc.voice.audio.ml.HeuristicVoiceProfile
import com.manc.voice.audio.ml.VoiceFeatures
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

/**
 * Headless end-to-end test of the processing chain (STFT -> noise estimate ->
 * VAD -> Wiener denoise -> voice features -> voice-lock gate -> ISTFT -> limiter),
 * the same algorithm AudioEngine runs, on synthetic owner / bystander / noise.
 * Asserts the owner survives while bystander speech and background are muted.
 */
class EngineChainTest {
    private val SR = 16000

    private fun voice(f0: Double, formants: DoubleArray, n: Int, amp: Double, phase: Double): FloatArray {
        val x = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            var s = 0.0
            var h = 1
            while (h <= 24) {
                val fr = f0 * h
                if (fr > SR / 2) break
                var g = 0.0
                for (fc in formants) g += 1.0 / (1.0 + ((fr - fc) / 90.0).pow(2))
                s += g * sin(2 * PI * fr * t + phase * h)
                h++
            }
            val env = 0.6 + 0.4 * max(0.0, sin(2 * PI * 4.0 * t))
            x[i] = (amp * env * s / 12.0).toFloat()
        }
        return x
    }
    private fun noise(n: Int, amp: Double, seed: Long): FloatArray {
        val r = java.util.Random(seed); val x = FloatArray(n); var lp = 0.0
        for (i in 0 until n) { val w = r.nextGaussian(); lp = 0.9 * lp + 0.1 * w; x[i] = (amp * (0.7 * w + 0.6 * lp)).toFloat() }
        return x
    }
    private fun rms(x: FloatArray, a: Int, b: Int): Double { var s = 0.0; for (i in a until b) s += x[i] * x[i]; return sqrt(s / (b - a)) }
    private fun db(x: Double) = 20 * log10(max(x, 1e-6))

    private class Chain(strength: Double, SR: Int) {
        val F = 256; val hop = F / 2; val frameMs = 1000.0 * hop / SR
        val stft = Stft(F); val bins = stft.bins
        val ne = NoiseEstimator(bins); val vad = Vad(SR, bins, F)
        val den = SpectralDenoiser(bins).apply { this.strength = strength }
        val gate = VoiceLockGate(frameMs).apply { this.strength = strength; enabled = true }
        val lim = Limiter()
        val feat = VoiceFeatures(SR, bins, F); val prof = HeuristicVoiceProfile(feat.bands)
        val pr = FloatArray(512); var pp = 0
        val g = DoubleArray(bins); val pw = DoubleArray(bins); val sh = DoubleArray(feat.bands)
        var enroll = false
        private fun ordered(): FloatArray { val t = FloatArray(pr.size); var i = pp; for (k in pr.indices) { t[k] = pr[i]; i = (i + 1) % pr.size }; return t }
        fun run(input: FloatArray, out: FloatArray) {
            var off = 0
            while (off + hop <= input.size) {
                for (i in 0 until hop) { pr[pp] = input[off + i]; pp = (pp + 1) % pr.size }
                stft.analyze(input, off)
                for (k in 0 until bins) pw[k] = stft.mag[k] * stft.mag[k]
                val sp = vad.process(pw, ne.noisePower()); ne.update(pw, sp)
                val dg = den.computeGain(pw, ne.noisePower()); System.arraycopy(dg, 0, g, 0, bins)
                feat.spectralShape(stft.mag, sh); val f0 = feat.pitch(ordered(), pr.size); val vo = f0 > 0
                if (enroll) prof.addEnrollFrame(sh, f0, vo)
                val sim = prof.similarity(sh, f0, vo)
                val gg = gate.process(sp, sim, 6.0)
                for (k in 0 until bins) g[k] = g[k] * gg
                stft.synthesize(g, out, off)
                off += hop
            }
            lim.process(out, off)
        }
    }

    @Test fun ownerSurvivesBystanderAndNoiseMuted() {
        fun seg(sec: Double) = (sec * SR).toInt()
        val ownerFmt = doubleArrayOf(600.0, 1000.0, 2500.0)
        val bystFmt = doubleArrayOf(700.0, 1700.0, 3000.0)

        val chain = Chain(0.6, SR)
        val enrollClip = voice(120.0, ownerFmt, seg(3.0), 0.5, 0.3)
        val warm = FloatArray(enrollClip.size)
        chain.enroll = true; chain.run(enrollClip, warm); chain.prof.finishEnroll(); chain.enroll = false
        assertTrue("enrollment should succeed", chain.prof.isEnrolled)

        val parts = listOf(
            "ownerClean" to voice(120.0, ownerFmt, seg(1.5), 0.5, 0.3),
            "ownerNoisy" to FloatArray(seg(1.8)).also { val v = voice(120.0, ownerFmt, it.size, 0.5, 0.3); val nz = noise(it.size, 0.18, 11); for (i in it.indices) it[i] = v[i] + nz[i] },
            "bystNoisy" to FloatArray(seg(1.8)).also { val v = voice(210.0, bystFmt, it.size, 0.5, 1.1); val nz = noise(it.size, 0.18, 22); for (i in it.indices) it[i] = v[i] + nz[i] },
            "noiseOnly" to noise(seg(1.5), 0.18, 33)
        )
        var total = 0; for ((_, s) in parts) total += s.size
        val mix = FloatArray(total); var o = 0; val ranges = HashMap<String, IntRange>()
        for ((name, s) in parts) { System.arraycopy(s, 0, mix, o, s.size); ranges[name] = o until o + s.size; o += s.size }
        val out = FloatArray(mix.size)
        chain.run(mix, out)

        fun change(name: String): Double { val r = ranges[name]!!; return db(rms(out, r.first, r.last + 1)) - db(rms(mix, r.first, r.last + 1)) }
        val ownerNoisyOut = rms(out, ranges["ownerNoisy"]!!.first, ranges["ownerNoisy"]!!.last + 1)
        val bystOut = rms(out, ranges["bystNoisy"]!!.first, ranges["bystNoisy"]!!.last + 1)

        val byst = change("bystNoisy"); val noiseOnly = change("noiseOnly"); val ownerNoisy = change("ownerNoisy")
        println("ownerNoisy=${"%.1f".format(ownerNoisy)}dB bystNoisy=${"%.1f".format(byst)}dB noiseOnly=${"%.1f".format(noiseOnly)}dB ownerOut=$ownerNoisyOut bystOut=$bystOut")

        assertTrue("background should be muted (<-12 dB), was $noiseOnly", noiseOnly < -12.0)
        assertTrue("bystander should be muted (<-12 dB), was $byst", byst < -12.0)
        assertTrue("owner should survive in noise (>-16 dB), was $ownerNoisy", ownerNoisy > -16.0)
        assertTrue("owner must pass much louder than bystander", ownerNoisyOut > 2.5 * bystOut)
    }
}
