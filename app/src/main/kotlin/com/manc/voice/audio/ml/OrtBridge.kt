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

    class Session internal constructor(val env: OrtEnvironment, val session: OrtSession) {
        val inputNames: List<String> get() = session.inputNames.toList()
        val outputNames: List<String> get() = session.outputNames.toList()
        fun close() { try { session.close() } catch (_: Throwable) {} }
    }

    fun open(modelFile: File, threads: Int = 1): Session? = try {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(threads)
        Session(env, env.createSession(modelFile.absolutePath, opts))
    } catch (e: Throwable) { null }

    /** Returns (env, session, firstInputName) or null on any failure. */
    fun createSession(modelFile: File): Triple<Any, Any, String>? {
        val s = open(modelFile) ?: return null
        val input = s.inputNames.firstOrNull() ?: return null
        return Triple(s.env, s.session, input)
    }

    /** Runs a single-input session and returns the first output flattened to FloatArray. */
    fun run(env: Any, session: Any, inputName: String, data: FloatBuffer, shape: LongArray): FloatArray? {
        if (env !is OrtEnvironment || session !is OrtSession) return null
        var tensor: OnnxTensor? = null
        return try {
            tensor = OnnxTensor.createTensor(env, data, shape)
            val result = session.run(mapOf(inputName to tensor))
            val flat = flatten(result.get(0).value)
            result.close()
            flat
        } catch (e: Throwable) { null } finally { tensor?.close() }
    }

    /**
     * Multi-input / multi-output run. inputs: name -> (flat data, shape).
     * Returns name -> flattened float output for every output of the session.
     */
    fun runMulti(s: Session, inputs: Map<String, Pair<FloatArray, LongArray>>): Map<String, FloatArray>? {
        val tensors = HashMap<String, OnnxTensor>()
        return try {
            for ((name, v) in inputs) {
                tensors[name] = OnnxTensor.createTensor(s.env, FloatBuffer.wrap(v.first), v.second)
            }
            val result = s.session.run(tensors)
            val out = HashMap<String, FloatArray>()
            val names = s.outputNames
            for (i in names.indices) {
                flatten(result.get(i).value)?.let { out[names[i]] = it }
            }
            result.close()
            out
        } catch (e: Throwable) { null } finally { tensors.values.forEach { try { it.close() } catch (_: Throwable) {} } }
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
