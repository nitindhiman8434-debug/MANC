package com.manc.voice.audio.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Thin wrapper over ONNX Runtime so the rest of the engine never touches the
 * ORT types directly (and so a missing runtime degrades gracefully).
 */
object OrtBridge {

    /** Returns (env, session, firstInputName) or null on any failure. */
    fun createSession(modelFile: File): Triple<Any, Any, String>? {
        return try {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(1)
            val session = env.createSession(modelFile.absolutePath, opts)
            val input = session.inputNames.iterator().next()
            Triple(env, session, input)
        } catch (e: Throwable) {
            null
        }
    }

    /** Runs a single-input session and returns the first output flattened to FloatArray. */
    fun run(env: Any, session: Any, inputName: String, data: FloatBuffer, shape: LongArray): FloatArray? {
        if (env !is OrtEnvironment || session !is OrtSession) return null
        var tensor: OnnxTensor? = null
        return try {
            tensor = OnnxTensor.createTensor(env, data, shape)
            val result = session.run(mapOf(inputName to tensor))
            val out = result.get(0)
            val flat = flatten(out.value)
            result.close()
            flat
        } catch (e: Throwable) {
            null
        } finally {
            tensor?.close()
        }
    }

    private fun flatten(value: Any?): FloatArray? {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> {
                val parts = value.mapNotNull { flatten(it) }
                if (parts.isEmpty()) null else {
                    val total = parts.sumOf { it.size }
                    val out = FloatArray(total)
                    var o = 0
                    for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
                    out
                }
            }
            else -> null
        }
    }
}
