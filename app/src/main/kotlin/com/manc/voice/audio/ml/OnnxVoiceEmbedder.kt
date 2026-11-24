package com.manc.voice.audio.ml

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Optional speaker-embedding voice-lock. Loads a bundled ONNX embedder
 * (e.g. a wespeaker / 3D-Speaker CAM++ export) and compares 1 s voice windows
 * to the enrolled owner by cosine similarity. Everything is guarded: if the
 * runtime or model is missing, or the contract does not match, it reports
 * unavailable and the engine keeps using the model-free profile.
 *
 * Expected model contract (discovered at runtime, not hard-coded indices):
 *   input : float [1, T, 80] log-mel  (name auto-detected)
 *   output: float [1, D]     embedding (first output)
 */
class OnnxVoiceEmbedder private constructor(
    private val session: Any,
    private val env: Any,
    private val inputName: String,
    private val logMel: LogMel
) {
    private var refEmb: FloatArray? = null
    private val enrollEmbs = ArrayList<FloatArray>()

    fun beginEnroll() { enrollEmbs.clear(); refEmb = null }

    fun addEnrollWindow(mono16k: FloatArray) {
        embed(mono16k)?.let { enrollEmbs.add(it) }
    }

    fun finishEnroll(): Boolean {
        if (enrollEmbs.isEmpty()) return false
        val d = enrollEmbs[0].size
        val mean = FloatArray(d)
        for (e in enrollEmbs) for (i in 0 until d) mean[i] += e[i]
        for (i in 0 until d) mean[i] /= enrollEmbs.size
        refEmb = l2norm(mean)
        return true
    }

    val isEnrolled get() = refEmb != null

    /** Cosine similarity mapped to [0,1]; null if it could not run. */
    fun similarity(window16k: FloatArray): Double? {
        val r = refEmb ?: return null
        val e = embed(window16k) ?: return null
        var dot = 0.0
        for (i in r.indices) dot += r[i] * e[i]
        return ((dot + 1.0) / 2.0).coerceIn(0.0, 1.0)
    }

    private fun embed(mono16k: FloatArray): FloatArray? {
        return try {
            val feats = logMel.extract(mono16k)
            if (feats.isEmpty()) return null
            val t = feats.size; val m = logMel.mels
            val buf = FloatBuffer.allocate(t * m)
            for (row in feats) for (v in row) buf.put(v.toFloat())
            buf.rewind()
            // Refltaction-free ORT calls guarded in a helper to keep this file compiling
            val emb = OrtBridge.run(env, session, inputName, buf, longArrayOf(1, t.toLong(), m.toLong()))
            emb?.let { l2norm(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "embed failed: ${e.message}")
            null
        }
    }

    private fun l2norm(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat().coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / n }
    }

    companion object {
        private const val TAG = "OnnxVoiceEmbedder"

        fun tryLoad(ctx: Context): OnnxVoiceEmbedder? {
            val model = ModelRegistry.speakerModel(ctx) ?: return null
            return try {
                val (env, session, input) = OrtBridge.createSession(model) ?: return null
                OnnxVoiceEmbedder(session, env, input, LogMel(16000, 80))
            } catch (e: Throwable) {
                Log.w(TAG, "load failed: ${e.message}"); null
            }
        }
    }
}
