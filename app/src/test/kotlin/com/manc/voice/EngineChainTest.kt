package com.manc.voice

import com.manc.voice.audio.dsp.CleanChain
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

/**
 * End-to-end test of the real cleaning pipeline (CleanChain, the exact code the
 * app runs) on synthetic owner / bystander / noise. Asserts the owner survives
 * (clean and in noise) while bystander speech and background are muted.
 */
class EngineChainTest {
    private val SR = 16000

    private fun voice(f0: Double, formants: DoubleArray, n: Int, amp: Double, phase: Double): FloatArray {
        val x = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            var s = 0.0; var h = 1
            while (h <= 24) {
                val fr = f0 * h; if (fr > SR / 2) break
                var g = 0.0
                for (fc in formants) g += 1.0 / (1.0 + ((fr - fc) / 160.0).pow(2))
                s += (0.15 + g) / h * sin(2 * PI * fr * t + phase * h); h++
            }
            val gap = (t % 0.6) > 0.48          // ~120 ms pause between "words"
            val env = if (gap) 0.0 else 0.6 + 0.4 * max(0.0, sin(2 * PI * 4.0 * t))
            x[i] = (amp * env * s / 3.0).toFloat()
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

    /** Feed the chain in 20 ms blocks, exactly like the foreground service does. */
    private fun runBlocks(chain: CleanChain, sig: FloatArray): FloatArray {
        val out = FloatArray(sig.size); val blk = SR / 50; var off = 0
        while (off + blk <= sig.size) {
            val b = FloatArray(blk)
            chain.process(sig.copyOfRange(off, off + blk), blk, b)
            System.arraycopy(b, 0, out, off, blk); off += blk
        }
        return out
    }

    @Test fun ownerSurvivesBystanderAndNoiseMuted() {
        fun seg(sec: Double) = (sec * SR).toInt()
        val ownerFmt = doubleArrayOf(600.0, 1000.0, 2500.0)
        val bystFmt = doubleArrayOf(700.0, 1700.0, 3000.0)

        val chain = CleanChain(SR).apply { strength = 0.85; hardMute = true; voiceLock = true }
        val enrollClip = voice(120.0, ownerFmt, seg(3.0), 0.5, 0.3)
        chain.beginEnroll(); chain.process(enrollClip, enrollClip.size, FloatArray(enrollClip.size))
        assertTrue("enrollment should succeed", chain.finishEnroll())

        val parts = listOf(
            "ownerClean" to voice(120.0, ownerFmt, seg(1.5), 0.5, 0.3),
            "ownerNoisy" to FloatArray(seg(2.0)).also { val v = voice(120.0, ownerFmt, it.size, 0.5, 0.3); val nz = noise(it.size, 0.18, 11); for (i in it.indices) it[i] = v[i] + nz[i] },
            "bystNoisy" to FloatArray(seg(2.0)).also { val v = voice(210.0, bystFmt, it.size, 0.5, 1.1); val nz = noise(it.size, 0.18, 22); for (i in it.indices) it[i] = v[i] + nz[i] },
            "noiseOnly" to noise(seg(1.5), 0.18, 33)
        )
        val results = HashMap<String, Double>(); val outs = HashMap<String, Double>()
        for ((name, sig) in parts) {
            val o = runBlocks(chain, sig)
            results[name] = db(rms(o, 0, o.size)) - db(rms(sig, 0, sig.size))
            outs[name] = rms(o, 0, o.size)
        }
        println("chain: " + results.entries.joinToString { "${it.key}=${"%.1f".format(it.value)}dB" })

        assertTrue("background must be muted (<-30 dB), was ${results["noiseOnly"]}", results["noiseOnly"]!! < -30.0)
        assertTrue("bystander must be muted (<-30 dB), was ${results["bystNoisy"]}", results["bystNoisy"]!! < -30.0)
        assertTrue("owner's clean voice must be preserved (>-3 dB), was ${results["ownerClean"]}", results["ownerClean"]!! > -3.0)
        assertTrue("owner must survive in noise (>-10 dB), was ${results["ownerNoisy"]}", results["ownerNoisy"]!! > -10.0)
        assertTrue("owner must pass much louder than bystander", outs["ownerNoisy"]!! > 4.0 * outs["bystNoisy"]!!)
    }

    /**
     * The neural speaker score is advisory and asymmetric: a low score from a noisy
     * embedding must NOT mute the owner on its own, while a low score plus a
     * "not the owner" voiceprint keeps a bystander muted.
     */
    @Test fun lowNeuralScoreNeverMutesOwnerAlone() {
        fun seg(sec: Double) = (sec * SR).toInt()
        val ownerFmt = doubleArrayOf(600.0, 1000.0, 2500.0)
        val bystFmt = doubleArrayOf(700.0, 1700.0, 3000.0)
        val chain = CleanChain(SR).apply { strength = 1.0; hardMute = true; voiceLock = true }
        val enrollClip = voice(120.0, ownerFmt, seg(3.0), 0.5, 0.3)
        chain.beginEnroll(); chain.process(enrollClip, enrollClip.size, FloatArray(enrollClip.size)); chain.finishEnroll()

        // learn moderate background first, like a real call
        val bg = noise(seg(1.5), 0.06, 55); runBlocks(chain, bg)

        // owner talks in noise while the neural model (confused by noise) reports 0.0
        chain.externalSimilarity = 0.0
        val ownerN = FloatArray(seg(2.5)).also { val v = voice(120.0, ownerFmt, it.size, 0.5, 0.3); val nz = noise(it.size, 0.06, 66); for (i in it.indices) it[i] = v[i] + nz[i] }
        val o1 = runBlocks(chain, ownerN)
        val ownerChange = db(rms(o1, 0, o1.size)) - db(rms(ownerN, 0, ownerN.size))

        // bystander talks in noise, neural also low -> must stay muted
        val bystN = FloatArray(seg(2.0)).also { val v = voice(210.0, bystFmt, it.size, 0.5, 1.1); val nz = noise(it.size, 0.06, 77); for (i in it.indices) it[i] = v[i] + nz[i] }
        val o2 = runBlocks(chain, bystN)
        val bystChange = db(rms(o2, 0, o2.size)) - db(rms(bystN, 0, bystN.size))
        println("asymmetric: owner=${"%.1f".format(ownerChange)}dB bystander=${"%.1f".format(bystChange)}dB")

        assertTrue("owner must still pass with a low neural score (>-10 dB), was $ownerChange", ownerChange > -10.0)
        assertTrue("bystander must stay muted (<-20 dB), was $bystChange", bystChange < -20.0)
    }
}
