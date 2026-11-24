package com.manc.voice.audio.ml

import android.content.Context
import java.io.File

/**
 * Resolves optional ONNX models. They may be shipped in assets/models or
 * downloaded to the app's files dir. Missing models are normal: the engine
 * falls back to its model-free path.
 */
object ModelRegistry {
    private fun fromAssetsOrFiles(ctx: Context, name: String): File? {
        val out = File(ctx.filesDir, "models/$name")
        if (out.exists() && out.length() > 0) return out
        return try {
            ctx.assets.open("models/$name").use { input ->
                out.parentFile?.mkdirs()
                out.outputStream().use { input.copyTo(it) }
            }
            if (out.length() > 0) out else null
        } catch (e: Exception) { null }
    }

    fun speakerModel(ctx: Context): File? = fromAssetsOrFiles(ctx, "speaker.onnx")
    fun denoiserModel(ctx: Context): File? = fromAssetsOrFiles(ctx, "denoiser.onnx")

    fun listAvailable(ctx: Context): List<String> {
        val found = mutableListOf<String>()
        if (speakerModel(ctx) != null) found += "speaker.onnx"
        if (denoiserModel(ctx) != null) found += "denoiser.onnx"
        return found
    }
}
