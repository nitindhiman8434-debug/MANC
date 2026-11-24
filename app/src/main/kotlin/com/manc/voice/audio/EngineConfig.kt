package com.manc.voice.audio

data class EngineConfig(
    var denoiseEnabled: Boolean = true,
    var voiceLockEnabled: Boolean = true,
    var nearFieldEnabled: Boolean = false,
    var hardMute: Boolean = true,        // gate closed -> true silence (fully mute background)
    var strength: Float = 0.85f,         // 0 calm .. 1 aggressive; default strong
    var makeupGain: Float = 1.7f,
    var bypass: Boolean = false
)
