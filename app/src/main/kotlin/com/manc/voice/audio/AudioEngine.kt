package com.manc.voice.audio

import android.content.Context
import com.manc.voice.audio.dsp.CleanChain
import com.manc.voice.audio.ml.OnnxDenoiser
import com.manc.voice.audio.ml.OnnxVoiceEmbedder
import kotlin.math.log10

/**
 * The full outgoing-voice pipeline on 16 kHz mono float blocks:
 *
 *   [GTCRN neural denoiser, if bundled] -> CleanChain (noise estimate, VAD,
 *   spectral denoise fallback, voice features on the denoised signal, owner
 *   similarity, voice-lock gate, limiter)  +  optional neural speaker embedder
 *   blended into the similarity.
 *
 * Never crashes, always produces audio, degrades cleanly without models.
 */
class AudioEngine(
    private val appCtx: Context,
    val sampleRate: Int = 16000,
    val config: EngineConfig = EngineConfig()
) {
    private val chain = CleanChain(sampleRate, 256)
    private val onnxEmbedder: OnnxVoiceEmbedder? = OnnxVoiceEmbedder.tryLoad(appCtx)
    private val onnxDenoiser: OnnxDenoiser? = OnnxDenoiser.tryLoad(appCtx)
    private var denoisedBuf = FloatArray(0)

    // sliding 1 s window of RAW input for the embedder (trained on natural speech)
    private val embWindow = FloatArray(sampleRate)
    private var embPos = 0
    private var samplesSinceEmbed = 0
    private var onnxSimPrior: Double? = null

    @Volatile var meters = Meters(); private set
    @Volatile var nearFieldDb: Float = 6f

    private var enrolling = false

    fun beginEnroll() { enrolling = true; chain.beginEnroll(); onnxEmbedder?.beginEnroll(); onnxSimPrior = null }
    fun finishEnroll(): Boolean {
        enrolling = false
        val a = chain.finishEnroll()
        val b = onnxEmbedder?.finishEnroll() ?: false
        return a || b
    }
    fun isEnrolled() = chain.profile.isEnrolled || (onnxEmbedder?.isEnrolled == true)
    fun exportProfile(): String = chain.profile.exportState()
    fun importProfile(s: String) { chain.profile.importState(s) }
    fun exportNeuralProfile(): String? = onnxEmbedder?.exportState()
    fun importNeuralProfile(s: String) { onnxEmbedder?.importState(s) }

    fun process(input: FloatArray, n: Int, out: FloatArray): Int {
        if (config.bypass) {
            System.arraycopy(input, 0, out, 0, n)
            publish(input, n, out, n, 0.0, 1.0, 1.0)
            return n
        }
        chain.strength = config.strength.toDouble()
        chain.hardMute = config.hardMute
        chain.voiceLock = config.voiceLockEnabled
        chain.nearFieldEnabled = config.nearFieldEnabled
        chain.nearFieldDb = nearFieldDb.toDouble()
        chain.makeupGain = config.makeupGain.toDouble()
        chain.enrolling = enrolling

        // 1) neural denoise first when available (then the spectral fallback is off)
        val useNeural = onnxDenoiser != null && config.denoiseEnabled
        val src: FloatArray = if (useNeural) {
            if (denoisedBuf.size < n) denoisedBuf = FloatArray(n)
            onnxDenoiser!!.wet = config.neuralWet
            onnxDenoiser!!.process(input, n, denoisedBuf)
            denoisedBuf
        } else input
        chain.spectralDenoise = config.denoiseEnabled && !useNeural

        // 2) neural speaker similarity on the (neural-)denoised audio every ~200 ms,
        //    the same signal used at enrollment, so noise cannot collapse the match
        onnxEmbedder?.let { emb ->
            for (i in 0 until n) { embWindow[embPos] = src[i]; embPos = (embPos + 1) % embWindow.size }
            samplesSinceEmbed += n
            if (samplesSinceEmbed >= sampleRate / 5) {
                samplesSinceEmbed = 0
                val win = FloatArray(embWindow.size)
                var idx = embPos
                for (i in win.indices) { win[i] = embWindow[idx]; idx = (idx + 1) % embWindow.size }
                if (enrolling) emb.addEnrollWindow(win)
                else if (emb.isEnrolled) emb.similarity(win)?.let { onnxSimPrior = it }
            }
        }
        chain.externalSimilarity = if (!enrolling && onnxEmbedder?.isEnrolled == true) onnxSimPrior else null

        // 3) gate + fallback denoise + limiter
        val produced = chain.process(src, n, out)
        publish(input, n, out, produced, chain.lastSpeech, chain.lastSim, chain.lastGate)
        return produced
    }

    private fun publish(inp: FloatArray, nIn: Int, outp: FloatArray, nOut: Int, speech: Double, sim: Double, g: Double) {
        meters = Meters(
            inputRms = CleanChain.rms(inp, nIn),
            outputRms = CleanChain.rms(outp, nOut),
            speechProb = speech.toFloat(),
            ownerSimilarity = sim.toFloat(),
            gate = g.toFloat(),
            nearFieldDb = nearFieldDb,
            usingOnnxEmbedder = (onnxEmbedder?.isEnrolled == true),
            usingOnnxDenoiser = (onnxDenoiser != null),
            locked = config.voiceLockEnabled && isEnrolled()
        )
    }

    companion object {
        fun dbfs(rms: Float): Float = if (rms <= 1e-6f) -120f else (20f * log10(rms))
    }
}
