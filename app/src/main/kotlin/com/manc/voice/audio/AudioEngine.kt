package com.manc.voice.audio

import android.content.Context
import com.manc.voice.audio.dsp.*
import com.manc.voice.audio.ml.*
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The full outgoing-voice pipeline, operating on 16 kHz mono float blocks:
 *
 *   STFT -> noise estimate -> VAD -> spectral denoise gain
 *        -> voice features -> owner similarity (model-free, optionally ONNX)
 *        -> voice-lock gate (scalar) -> combine -> ISTFT -> limiter
 *
 * Design goals: never crash, always produce audio, and degrade cleanly when
 * optional ONNX models are absent.
 */
class AudioEngine(
    private val appCtx: Context,
    val sampleRate: Int = 16000,
    val config: EngineConfig = EngineConfig()
) {
    private val frameSize = 256
    private val hop = frameSize / 2               // 128 samples = 8 ms @16k
    private val frameMs = 1000.0 * hop / sampleRate
    private val stft = Stft(frameSize)
    private val bins = stft.bins
    private val noiseEst = NoiseEstimator(bins)
    private val vad = Vad(sampleRate, bins, frameSize)
    private val denoiser = SpectralDenoiser(bins)
    private val gate = VoiceLockGate(frameMs)
    private val limiter = Limiter()
    private val features = VoiceFeatures(sampleRate, bins, frameSize)
    private val profile: VoiceProfile = HeuristicVoiceProfile(features.bands)

    private val onnxEmbedder: OnnxVoiceEmbedder? = OnnxVoiceEmbedder.tryLoad(appCtx)

    private val power = DoubleArray(bins)
    private val shape = DoubleArray(features.bands)
    private val gainBuf = DoubleArray(bins)
    private val pitchRing = FloatArray(512)
    private var pitchPos = 0

    // sliding 1 s window for the optional ONNX embedder
    private val embWindow = FloatArray(sampleRate)
    private var embPos = 0
    private var hopsSinceEmbed = 0
    private var onnxSimPrior = 1.0

    @Volatile var meters = Meters(); private set
    @Volatile var nearFieldDb: Float = 6f    // supplied by the engine host (phone-mic cue)

    private var enrolling = false

    fun setStrength(s: Float) { denoiser.strength = s.toDouble(); gate.strength = s.toDouble() }

    fun beginEnroll() { enrolling = true; profile.beginEnroll(); onnxEmbedder?.beginEnroll() }
    fun finishEnroll(): Boolean {
        enrolling = false
        val a = profile.finishEnroll()
        val b = onnxEmbedder?.finishEnroll() ?: false
        return a || b
    }
    fun isEnrolled() = profile.isEnrolled || (onnxEmbedder?.isEnrolled == true)
    fun exportProfile(): String = profile.exportState()
    fun importProfile(s: String) { profile.importState(s) }

    /**
     * Process [n] input samples (16 kHz mono) into [out]. Returns samples written.
     * out must be at least n long. n should be a multiple of hop for exactness;
     * remainder is buffered internally by the STFT.
     */
    fun process(input: FloatArray, n: Int, out: FloatArray): Int {
        gate.enabled = config.voiceLockEnabled
        gate.nearFieldEnabled = config.nearFieldEnabled
        setStrength(config.strength)
        limiter.makeupGain = config.makeupGain.toDouble()

        if (config.bypass) {
            System.arraycopy(input, 0, out, 0, n)
            updateMeters(input, n, input, n, 0.0, 1.0, 1.0)
            return n
        }

        var produced = 0
        var off = 0
        var lastSpeech = 0.0; var lastSim = 1.0; var lastGate = 1.0
        while (off + hop <= n) {
            // feed pitch ring
            for (i in 0 until hop) { pitchRing[pitchPos] = input[off + i]; pitchPos = (pitchPos + 1) % pitchRing.size }
            // feed embedder window
            for (i in 0 until hop) { embWindow[embPos] = input[off + i]; embPos = (embPos + 1) % embWindow.size }

            stft.analyze(input, off)
            for (k in 0 until bins) power[k] = stft.mag[k] * stft.mag[k]

            val speech = vad.process(power, noiseEst.noisePower())
            noiseEst.update(power, speech)

            // denoise gain
            if (config.denoiseEnabled) {
                val g = denoiser.computeGain(power, noiseEst.noisePower())
                System.arraycopy(g, 0, gainBuf, 0, bins)
            } else {
                for (k in 0 until bins) gainBuf[k] = 1.0
            }

            // voice features + owner similarity
            features.spectralShape(stft.mag, shape)
            val f0 = features.pitch(orderedPitch(), pitchRing.size)
            val voiced = f0 > 0
            var sim = profile.similarity(shape, f0, voiced)

            if (enrolling) {
                profile.addEnrollFrame(shape, f0, voiced)
            }

            // optional ONNX embedder as a slow prior
            onnxEmbedder?.let { emb ->
                hopsSinceEmbed++
                val stride = (0.2 / (frameMs / 1000.0)).toInt().coerceAtLeast(1) // ~200 ms
                if (hopsSinceEmbed >= stride) {
                    hopsSinceEmbed = 0
                    val win = orderedEmbWindow()
                    if (enrolling) emb.addEnrollWindow(win)
                    else emb.similarity(win)?.let { onnxSimPrior = it }
                }
                if (emb.isEnrolled && !enrolling) {
                    sim = 0.5 * sim + 0.5 * onnxSimPrior
                }
            }

            val gateGain = gate.process(speech, sim, nearFieldDb.toDouble())

            for (k in 0 until bins) gainBuf[k] = gainBuf[k] * gateGain
            stft.synthesize(gainBuf, out, off)

            lastSpeech = speech; lastSim = sim; lastGate = gateGain
            produced += hop
            off += hop
        }
        // copy any remainder straight through (keeps length), rare if n multiple of hop
        if (produced < n) {
            for (i in produced until n) out[i] = 0f
        }
        limiter.process(out, produced)
        updateMeters(input, n, out, produced, lastSpeech, lastSim, lastGate)
        return produced
    }

    private fun orderedPitch(): FloatArray {
        // return pitchRing rotated so it's time-ordered
        val tmp = FloatArray(pitchRing.size)
        var idx = pitchPos
        for (i in pitchRing.indices) { tmp[i] = pitchRing[idx]; idx = (idx + 1) % pitchRing.size }
        return tmp
    }

    private fun orderedEmbWindow(): FloatArray {
        val tmp = FloatArray(embWindow.size)
        var idx = embPos
        for (i in embWindow.indices) { tmp[i] = embWindow[idx]; idx = (idx + 1) % embWindow.size }
        return tmp
    }

    private fun updateMeters(inp: FloatArray, nIn: Int, outp: FloatArray, nOut: Int,
                             speech: Double, sim: Double, g: Double) {
        meters = Meters(
            inputRms = rms(inp, nIn),
            outputRms = rms(outp, nOut),
            speechProb = speech.toFloat(),
            ownerSimilarity = sim.toFloat(),
            gate = g.toFloat(),
            nearFieldDb = nearFieldDb,
            usingOnnxEmbedder = (onnxEmbedder?.isEnrolled == true),
            locked = config.voiceLockEnabled && isEnrolled()
        )
    }

    private fun rms(x: FloatArray, n: Int): Float {
        if (n <= 0) return 0f
        var s = 0.0
        for (i in 0 until n) s += x[i] * x[i]
        return sqrt(s / n).toFloat()
    }

    companion object {
        fun dbfs(rms: Float): Float = if (rms <= 1e-6f) -120f else (20f * log10(rms))
    }
}
