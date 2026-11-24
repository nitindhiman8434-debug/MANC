package com.manc.voice.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.manc.voice.Controller
import com.manc.voice.MainActivity
import com.manc.voice.MancApp
import com.manc.voice.R
import com.manc.voice.audio.AudioEngine
import com.manc.voice.data.Prefs
import org.json.JSONObject
import com.manc.voice.shizuku.ShizukuManager
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Foreground service that wires the shell bridge to the on-device engine:
 *   bridge mic -> [pipe] -> engine (denoise + voice-lock) -> [pipe] -> bridge injector.
 */
class VoiceService : Service() {
    private val TAG = "VoiceService"
    private val SR = 16000
    @Volatile private var running = false
    private var worker: Thread? = null
    private lateinit var engine: AudioEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForegroundSafe()
        if (!running) start()
        return START_STICKY
    }

    private fun startForegroundSafe() {
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, MancApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_running))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
    }

    private fun start() {
        val bridge = ShizukuManager.bridge
        if (bridge == null) {
            Controller.setStatus("Bridge not connected. Open setup and grant Shizuku.")
            Controller.setRun(Controller.Run.ERROR)
            stopSelf(); return
        }
        engine = AudioEngine(applicationContext)
        engine.config.denoiseEnabled = Controller.config.denoiseEnabled
        engine.config.voiceLockEnabled = Controller.config.voiceLockEnabled
        engine.config.nearFieldEnabled = Controller.config.nearFieldEnabled
        engine.config.strength = Controller.config.strength
        engine.config.makeupGain = Controller.config.makeupGain
        engine.config.bypass = Controller.config.bypass
        engine.config.neuralWet = Controller.config.neuralWet
        Controller.enrolledProfile?.let { engine.importProfile(it) }
        Controller.enrolledNeural?.let { engine.importNeuralProfile(it) }

        running = true
        Controller.setRun(Controller.Run.STARTING)
        worker = Thread { loop(bridge) }.apply { isDaemon = true; start() }
    }

    private fun loop(bridge: com.manc.voice.bridge.IVoiceBridge) {
        var micRead: ParcelFileDescriptor? = null
        var cleanPipe: Array<ParcelFileDescriptor>? = null
        try {
            micRead = bridge.startMicCapture(SR) ?: run {
                Controller.setStatus("Microphone capture failed in the bridge.")
                Controller.setRun(Controller.Run.ERROR); return
            }
            cleanPipe = ParcelFileDescriptor.createPipe()
            val cleanRead = cleanPipe[0]
            val cleanWrite = cleanPipe[1]

            val mode = Controller.mode.value
            val ok: Boolean = when (mode) {
                Controller.Mode.AUTO -> {
                    val r = bridge.startCleanEverywhere(cleanRead, SR)
                    val j = try { JSONObject(r) } catch (e: Exception) { JSONObject() }
                    val apps = j.optBoolean("apps", false); val cell = j.optBoolean("cellular", false)
                    Controller.setSinks(buildString {
                        if (apps) append("all apps")
                        if (apps && cell) append(" + ")
                        if (cell) append("cellular")
                        if (!apps && !cell) append("none")
                    })
                    j.optBoolean("started", false)
                }
                Controller.Mode.VOIP -> {
                    val pkg = Controller.targetPackage.value
                    if (pkg != null) {
                        val uid = try { packageManager.getPackageUid(pkg, 0) } catch (e: Exception) { -1 }
                        if (uid >= 0) bridge.startInjectionForUid(uid, cleanRead, SR)
                        else bridge.startInjectionForPreset(7, cleanRead, SR)
                    } else bridge.startInjectionForPreset(7, cleanRead, SR)
                }
                Controller.Mode.CELLULAR -> {
                    val report = bridge.startCellularRedirect(cleanRead, SR)
                    Controller.setCellularReport(report)
                    report.contains("\"started\":true")
                }
            }
            if (!ok) {
                Controller.setStatus(
                    when (mode) {
                        Controller.Mode.CELLULAR -> "Cellular path not available on this device (see Cellular Lab report)."
                        Controller.Mode.AUTO -> "Could not install the virtual microphone. Is the Shizuku bridge connected?"
                        else -> "Could not install the virtual microphone for the selected app."
                    }
                )
                Controller.setRun(Controller.Run.ERROR)
                return
            }

            Controller.setRun(Controller.Run.RUNNING)
            Controller.setStatus(when (mode) {
                Controller.Mode.CELLULAR -> "Cleaning cellular uplink."
                Controller.Mode.AUTO -> "Cleaning everywhere: ${Controller.sinks.value}."
                else -> "Cleaning your microphone."
            })

            val input = FileInputStream(micRead.fileDescriptor)
            val output = FileOutputStream(cleanWrite.fileDescriptor)
            val chunk = SR / 50 // 20 ms
            val inBytes = ByteArray(chunk * 2)
            val inFloat = FloatArray(chunk)
            val outFloat = FloatArray(chunk)
            val outBytes = ByteArray(chunk * 2)

            while (running) {
                var filled = 0
                while (filled < inBytes.size) {
                    val r = input.read(inBytes, filled, inBytes.size - filled)
                    if (r <= 0) { running = false; break }
                    filled += r
                }
                if (!running) break
                val n = filled / 2
                for (i in 0 until n) {
                    val lo = inBytes[i * 2].toInt() and 0xFF
                    val hi = inBytes[i * 2 + 1].toInt()
                    inFloat[i] = (((hi shl 8) or lo).toShort()).toInt() / 32768f
                }
                // apply live config + enrollment requests
                applyConfig()
                if (Controller.enrollRequested) { Controller.enrollRequested = false; engine.beginEnroll() }
                if (Controller.enrollFinishRequested) {
                    Controller.enrollFinishRequested = false
                    val ok2 = engine.finishEnroll()
                    val prof = engine.exportProfile()
                    Controller.enrolledProfile = prof
                    Controller.setEnrolled(ok2)
                    if (ok2) Prefs.saveVoiceprintAsync(applicationContext, prof)
                    engine.exportNeuralProfile()?.let { np -> Controller.enrolledNeural = np; Prefs.saveNeuralVoiceprintAsync(applicationContext, np) }
                }

                val produced = engine.process(inFloat, n, outFloat)
                for (i in 0 until produced) {
                    var s = (outFloat[i] * 32767f).toInt()
                    if (s > 32767) s = 32767 else if (s < -32768) s = -32768
                    outBytes[i * 2] = (s and 0xFF).toByte()
                    outBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                output.write(outBytes, 0, produced * 2)
                Controller.publishMeters(engine.meters)
            }
            try { output.close() } catch (_: Throwable) {}
        } catch (e: Throwable) {
            Log.e(TAG, "loop error", e)
            Controller.setStatus("Audio loop error: ${e.message}")
            Controller.setRun(Controller.Run.ERROR)
        } finally {
            try { bridge.stopInjection() } catch (_: Throwable) {}
            try { bridge.stopCellularRedirect() } catch (_: Throwable) {}
            try { bridge.stopMicCapture() } catch (_: Throwable) {}
            try { micRead?.close() } catch (_: Throwable) {}
        }
    }

    private fun applyConfig() {
        engine.config.denoiseEnabled = Controller.config.denoiseEnabled
        engine.config.voiceLockEnabled = Controller.config.voiceLockEnabled
        engine.config.nearFieldEnabled = Controller.config.nearFieldEnabled
        engine.config.strength = Controller.config.strength
        engine.config.makeupGain = Controller.config.makeupGain
        engine.config.bypass = Controller.config.bypass
    }

    override fun onDestroy() {
        running = false
        try { worker?.join(300) } catch (_: Throwable) {}
        Controller.setRun(Controller.Run.STOPPED)
        Controller.publishMeters(com.manc.voice.audio.Meters())
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.manc.voice.STOP"
    }
}
