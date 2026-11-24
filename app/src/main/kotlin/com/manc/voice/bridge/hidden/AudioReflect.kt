package com.manc.voice.bridge.hidden

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.lang.reflect.Method

/**
 * Reflection wrappers around the hidden audio-policy injection API. Runs inside
 * the Shizuku shell process, which holds MODIFY_AUDIO_ROUTING / CAPTURE_* /
 * CALL_AUDIO_INTERCEPTION. Approach adapted from scrcpy (Apache-2.0); see NOTICE.
 */
@SuppressLint("PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
object AudioReflect {
    private const val TAG = "AudioReflect"

    // MediaRecorder.AudioSource capture presets commonly used by call/VoIP apps.
    val CALL_PRESETS = intArrayOf(7 /*VOICE_COMMUNICATION*/, 1 /*MIC*/, 0 /*DEFAULT*/, 6 /*VOICE_RECOGNITION*/)

    private val amrClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
    private val amrBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
    private val amClass = Class.forName("android.media.audiopolicy.AudioMix")
    private val amBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
    private val apClass = Class.forName("android.media.audiopolicy.AudioPolicy")
    private val apBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")

    private val RULE_MATCH_UID = 4
    private val RULE_MATCH_CAPTURE_PRESET = 2
    private val MIX_ROLE_INJECTOR = amrClass.getField("MIX_ROLE_INJECTOR").getInt(null)
    private val ROUTE_FLAG_LOOP_BACK = amClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)

    class InjectionHandle(
        private val policy: Any,
        private val tracks: List<AudioTrack>
    ) {
        fun start() { tracks.forEach { try { it.play() } catch (_: Throwable) {} } }
        fun write(pcm: ShortArray, len: Int) {
            for (t in tracks) {
                try { t.write(pcm, 0, len, AudioTrack.WRITE_NON_BLOCKING) } catch (_: Throwable) {}
            }
        }
        fun release() {
            tracks.forEach { try { it.stop() } catch (_: Throwable) {}; try { it.release() } catch (_: Throwable) {} }
            unregister(policy)
        }
    }

    private fun capturePresetAttributes(preset: Int, forCall: Boolean): AudioAttributes {
        val b = AudioAttributes.Builder()
        try {
            val m = AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            m.invoke(b, preset)
        } catch (_: Throwable) {
            try {
                val m = AudioAttributes.Builder::class.java.getMethod("setCapturePreset", Int::class.javaPrimitiveType)
                m.invoke(b, preset)
            } catch (_: Throwable) {}
        }
        if (forCall) {
            try {
                val m = AudioAttributes.Builder::class.java.getMethod("setForCallRedirection")
                m.invoke(b)
            } catch (_: Throwable) {}
        }
        return b.build()
    }

    private fun buildMixForPreset(uid: Int?, preset: Int, format: AudioFormat, forCall: Boolean): Any {
        val ruleBuilder = amrBuilderClass.getConstructor().newInstance()
        val addMixRule: Method = amrBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        // capture preset (recorder rule)
        addMixRule.invoke(ruleBuilder, RULE_MATCH_CAPTURE_PRESET, capturePresetAttributes(preset, forCall))
        // optional UID narrowing; otherwise EXCLUDE our own process so the bridge's
        // microphone capture is never redirected to its own injected stream (feedback loop)
        // Exclusions go through excludeMixRule(RULE_MATCH_UID, ...): addMixRule only accepts
        // the plain RULE_MATCH_* codes and rejects 0x8004 with "Illegal rule value".
        if (uid != null) addMixRule.invoke(ruleBuilder, RULE_MATCH_UID, Integer.valueOf(uid))
        else amrBuilderClass.getMethod("excludeMixRule", Int::class.javaPrimitiveType, Any::class.java)
            .invoke(ruleBuilder, RULE_MATCH_UID, Integer.valueOf(android.os.Process.myUid()))
        amrBuilderClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType).invoke(ruleBuilder, MIX_ROLE_INJECTOR)
        val rule = amrBuilderClass.getMethod("build").invoke(ruleBuilder)

        val mixBuilder = amBuilderClass.getConstructor(amrClass).newInstance(rule)
        amBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        amBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK)
        return amBuilderClass.getMethod("build").invoke(mixBuilder)
    }

    /**
     * Register an injector policy. Creates one mix per preset (optionally UID-narrowed),
     * returns a handle whose tracks you write mono PCM16 to. The matching recorder
     * (the call/VoIP app) then reads the injected audio as its microphone.
     */
    fun registerInjector(uid: Int?, presets: IntArray, sampleRate: Int, forCall: Boolean): InjectionHandle? {
        ShellContext.prepare()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val mixes = ArrayList<Any>()
        for (p in presets) {
            try { mixes.add(buildMixForPreset(uid, p, format, forCall)) }
            catch (e: Throwable) { BridgeLog.w(TAG, "mix build failed preset=$p: ${BridgeLog.describe(e)}") }
        }
        if (mixes.isEmpty()) return null

        val policyBuilder = apBuilderClass.getConstructor(android.content.Context::class.java)
            .newInstance(ShellContext.context)
        val addMix = apBuilderClass.getMethod("addMix", amClass)
        for (m in mixes) addMix.invoke(policyBuilder, m)
        val policy = apBuilderClass.getMethod("build").invoke(policyBuilder)

        val reg = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", apClass)
        reg.isAccessible = true
        val result = reg.invoke(null, policy) as Int
        if (result != 0) { BridgeLog.e(TAG, "registerAudioPolicy returned $result"); return null }

        val createTrack = apClass.getMethod("createAudioTrackSource", amClass)
        val tracks = ArrayList<AudioTrack>()
        for (m in mixes) {
            try {
                val t = createTrack.invoke(policy, m) as AudioTrack
                tracks.add(t)
            } catch (e: Throwable) { BridgeLog.w(TAG, "createAudioTrackSource: ${BridgeLog.describe(e)}") }
        }
        if (tracks.isEmpty()) {
            BridgeLog.e(TAG, "no injector track could be created")
            unregister(policy)
            return null
        }
        BridgeLog.i(TAG, "virtual mic registered: ${tracks.size} of ${mixes.size} mixes, uid=${uid ?: "all"}")
        return InjectionHandle(policy, tracks)
    }

    private fun unregister(policy: Any) {
        for (name in arrayOf("unregisterAudioPolicyStatic", "unregisterAudioPolicyAsyncStatic")) {
            try {
                val m = AudioManager::class.java.getDeclaredMethod(name, apClass)
                m.isAccessible = true
                m.invoke(null, policy)
                return
            } catch (_: Throwable) { }
        }
    }
}
