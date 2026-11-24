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
    @Volatile private var onnxSimPrior: Double? = null

    // The speaker model is far heavier than one 20 ms audio block allows. Run on the
    // audio thread (as it first was) it stalled the stream five times a second: on a
    // real call only about 80 % of the audio got through and the far end heard a
    // stuttering voice. It now runs on its own low-priority thread; the audio thread
    // only hands over the latest second of audio and reads the latest score.
    // one worker that goes away 10 s after the engine stops using it (an engine is built per call)
    private val embedExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 10, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue()
    ) { r -> Thread(r, "manc-speaker").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } }
        .apply { allowCoreThreadTimeOut(true) }
    private val embedBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Let a speaker-model job that is still running finish (enrollment must not lose it). */
    private fun drainEmbedder() {
        try { embedExecutor.submit { }.get(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) { }
    }

    @Volatile var meters = Meters(); private set
    @Volatile var nearFieldDb: Float = 6f

    private var enrolling = false

    fun beginEnroll() { drainEmbedder(); enrolling = true; chain.beginEnroll(); onnxEmbedder?.beginEnroll(); onnxSimPrior = null }
    fun finishEnroll(): Boolean {
        enrolling = false
        drainEmbedder()
        // The model-free profile decides: it knows whether enough voiced speech was
        // heard. The neural embedder would accept 5 s of anything, so it only follows.
        val a = chain.finishEnroll()
        val b = if (a) (onnxEmbedder?.finishEnroll() ?: false) else false
        return a
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

        // 2) neural speaker similarity on the (neural-)denoised audio, off this thread,
        //    the same signal used at enrollment, so noise cannot collapse the match
        onnxEmbedder?.let { emb ->
            for (i in 0 until n) { embWindow[embPos] = src[i]; embPos = (embPos + 1) % embWindow.size }
            samplesSinceEmbed += n
            if (samplesSinceEmbed >= sampleRate * 3 / 10) {          // every 300 ms, if the last one is done
                samplesSinceEmbed = 0
                if (embedBusy.compareAndSet(false, true)) {
                    val win = FloatArray(embWindow.size)
                    var idx = embPos
                    for (i in win.indices) { win[i] = embWindow[idx]; idx = (idx + 1) % embWindow.size }
                    val forEnrollment = enrolling
                    try {
                        embedExecutor.execute {
                            try {
                                if (forEnrollment) emb.addEnrollWindow(win)
                                else if (emb.isEnrolled) emb.similarity(win)?.let { onnxSimPrior = it }
                            } catch (_: Throwable) {
                            } finally { embedBusy.set(false) }
                        }
                    } catch (_: Throwable) { embedBusy.set(false) }
                }
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
            locked = config.voiceLockEnabled && isEnrolled(),
            enrollVoicedMs = if (enrolling) chain.profile.enrollVoicedFrames * 8 else 0
        )
    }

    companion object {
        fun dbfs(rms: Float): Float = if (rms <= 1e-6f) -120f else (20f * log10(rms))
    }
}
