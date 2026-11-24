package com.manc.voice.audio

data class EngineConfig(
    var denoiseEnabled: Boolean = true,
    var voiceLockEnabled: Boolean = true,
    var nearFieldEnabled: Boolean = false,
    var strength: Float = 0.5f,          // 0 calm .. 1 aggressive
    var makeupGain: Float = 1.6f,
    var bypass: Boolean = false
)
