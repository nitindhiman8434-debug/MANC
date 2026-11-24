package com.manc.voice

import com.manc.voice.audio.ml.HeuristicVoiceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A voiceprint must come from real speech, not from a cough, a click or a silent room. */
class VoiceEnrollTest {
    private val bands = 16
    private fun shape() = DoubleArray(bands) { 1.0 / bands }

    @Test fun tooLittleSpeechIsRefusedAndSavesNothing() {
        val p = HeuristicVoiceProfile(bands)
        p.beginEnroll()
        repeat(HeuristicVoiceProfile.MIN_ENROLL_VOICED_FRAMES - 1) { p.addEnrollFrame(shape(), 120.0, voiced = true) }
        assertFalse(p.finishEnroll())
        assertFalse(p.isEnrolled)
    }

    @Test fun silenceDoesNotCountAsSpeech() {
        val p = HeuristicVoiceProfile(bands)
        p.beginEnroll()
        repeat(2000) { p.addEnrollFrame(shape(), 0.0, voiced = false) } // 16 s of unvoiced frames
        assertEquals(0, p.enrollVoicedFrames)
        assertFalse(p.finishEnroll())
    }

    @Test fun enoughSpeechIsAccepted() {
        val p = HeuristicVoiceProfile(bands)
        p.beginEnroll()
        repeat(HeuristicVoiceProfile.MIN_ENROLL_VOICED_FRAMES) { p.addEnrollFrame(shape(), 120.0, voiced = true) }
        assertTrue(p.finishEnroll())
        assertTrue(p.isEnrolled)
    }

    @Test fun aFailedReTeachKeepsTheEarlierVoiceprint() {
        val p = HeuristicVoiceProfile(bands)
        p.beginEnroll()
        repeat(300) { p.addEnrollFrame(shape(), 120.0, voiced = true) }
        assertTrue(p.finishEnroll())
        val saved = p.exportState()
        p.beginEnroll()
        repeat(10) { p.addEnrollFrame(shape(), 200.0, voiced = true) }
        assertFalse(p.finishEnroll())
        assertTrue(p.isEnrolled)
        assertEquals(saved, p.exportState())
    }
}
