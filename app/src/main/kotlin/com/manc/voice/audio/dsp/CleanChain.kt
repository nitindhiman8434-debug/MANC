package com.manc.voice.audio.dsp

import com.manc.voice.audio.ml.HeuristicVoiceProfile
import com.manc.voice.audio.ml.VoiceFeatures
import kotlin.math.sqrt

/**
 * The complete model-free cleaning pipeline on 16 kHz mono float blocks:
 *
 *   STFT -> noise estimate -> VAD -> spectral denoise gain
 *        -> voice features ON THE DENOISED SIGNAL -> owner similarity
 *        -> voice-lock gate -> ISTFT (denoise x gate) -> limiter
 *
 * Pure Kotlin so the exact same code runs in the app, the unit tests and the
 * offline harness. A neural denoiser (GTCRN) can pre-clean the input, in which
 * case [spectralDenoise] is turned off and the chain only gates and limits.
 */
class CleanChain(val sampleRate: Int = 16000, val frameSize: Int = 256) {
    val hop = frameSize / 2
    val frameMs = 1000.0 * hop / sampleRate
    private val stft = Stft(frameSize)         // output path (denoise x gate)
    private val stftFeat = Stft(frameSize)     // feature path (denoise only)
    val bins = stft.bins
    private val noiseEst = NoiseEstimator(bins)
    private val vad = Vad(sampleRate, bins, frameSize)
    private val denoiser = SpectralDenoiser(bins)
    val gate = VoiceLockGate(frameMs)
    private val limiter = Limiter()
    val features = VoiceFeatures(sampleRate, bins, frameSize)
    val profile = HeuristicVoiceProfile(features.bands)

    var strength: Double = 0.85
        set(v) { field = v; denoiser.strength = v; gate.strength = v }
    var hardMute: Boolean = true
    var spectralDenoise: Boolean = true
    var voiceLock: Boolean = true
    var nearFieldEnabled: Boolean = false
    var nearFieldDb: Double = 6.0
    var makeupGain: Double = 1.7
    /** Optional external (neural) similarity for the current block; blended 50/50 when set. */
    var externalSimilarity: Double? = null
    var enrolling: Boolean = false
        set(v) { field = v; noiseEst.freeze = v }

    private val power = DoubleArray(bins)
    private val denMag = DoubleArray(bins)
    private val gainBuf = DoubleArray(bins)
    private val featGain = DoubleArray(bins)
    private val shape = DoubleArray(features.bands)
    private val featOut = FloatArray(hop)
    private val pitchRing = FloatArray(1024)
    private var pitchPos = 0
    private val pitchTmp = FloatArray(1024)
    private var speechNoEvidenceMs = 0.0

    var lastSpeech = 0.0; private set
    var lastSim = 1.0; private set
    var lastGate = 1.0; private set
    var lastF0 = 0.0; private set

    fun beginEnroll() { enrolling = true; profile.beginEnroll() }
    fun finishEnroll(): Boolean { enrolling = false; return profile.finishEnroll() }

    /** Processes n samples into out (same length). Remainder beyond whole hops is zeroed. */
    fun process(input: FloatArray, n: Int, out: FloatArray): Int {
        gate.enabled = voiceLock
        gate.hardMute = hardMute
        gate.nearFieldEnabled = nearFieldEnabled
        limiter.makeupGain = makeupGain
        var off = 0
        while (off + hop <= n) {
            stft.analyze(input, off)
            stftFeat.analyze(input, off)
            for (k in 0 until bins) power[k] = stft.mag[k] * stft.mag[k]

            val speech = vad.process(power, noiseEst.noisePower())
            noiseEst.update(power, speech)

            if (spectralDenoise) {
                val g = denoiser.computeGain(power, noiseEst.noisePower())
                System.arraycopy(g, 0, featGain, 0, bins)
            } else {
                for (k in 0 until bins) featGain[k] = 1.0
            }
            // features on the DENOISED signal: shape from denoised magnitude,
            // pitch from a denoise-only reconstruction (robust in noise)
            for (k in 0 until bins) denMag[k] = stft.mag[k] * featGain[k]
            features.spectralShape(denMag, shape)
            stftFeat.synthesize(featGain, featOut, 0)
            for (i in 0 until hop) { pitchRing[pitchPos] = featOut[i]; pitchPos = (pitchPos + 1) % pitchRing.size }
            var idx = pitchPos
            for (i in pitchTmp.indices) { pitchTmp[i] = pitchRing[idx]; idx = (idx + 1) % pitchRing.size }
            val f0 = features.combPitch(pitchTmp, pitchTmp.size)
            val voiced = f0 > 0
            if (enrolling) profile.addEnrollFrame(shape, f0, voiced)
            var sim = profile.similarity(shape, f0, voiced)
            externalSimilarity?.let { sim = 0.5 * sim + 0.5 * it }

            // Fail-open safety: never hard-mute someone who is clearly speaking when we
            // simply lack identity evidence (no pitch, external model absent). Hard mute
            // is for silence/background, or a bystander we can positively tell apart.
            val hasEvidence = voiced || externalSimilarity != null
            if (speech > 0.58 && !hasEvidence) speechNoEvidenceMs += frameMs else speechNoEvidenceMs = 0.0
            if (speechNoEvidenceMs > 250.0 && lastGate < 0.5) sim = kotlin.math.max(sim, 0.85)

            val gateGain = gate.process(speech, sim, nearFieldDb)
            for (k in 0 until bins) gainBuf[k] = featGain[k] * gateGain
            stft.synthesize(gainBuf, out, off)

            lastSpeech = speech; lastSim = sim; lastGate = gateGain; lastF0 = f0
            off += hop
        }
        for (i in off until n) out[i] = 0f
        limiter.process(out, off)
        return off
    }

    companion object {
        fun rms(x: FloatArray, n: Int): Float {
            if (n <= 0) return 0f
            var s = 0.0
            for (i in 0 until n) s += x[i] * x[i]
            return sqrt(s / n).toFloat()
        }
    }
}
