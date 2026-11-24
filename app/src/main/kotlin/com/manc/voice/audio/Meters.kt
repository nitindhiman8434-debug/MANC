package com.manc.voice.audio

/** Live telemetry for the UI; updated by the engine each block. */
data class Meters(
    val inputRms: Float = 0f,
    val outputRms: Float = 0f,
    val speechProb: Float = 0f,
    val ownerSimilarity: Float = 0f,
    val gate: Float = 0f,
    val nearFieldDb: Float = 0f,
    val usingOnnxEmbedder: Boolean = false,
    val usingOnnxDenoiser: Boolean = false,
    val locked: Boolean = false
)
