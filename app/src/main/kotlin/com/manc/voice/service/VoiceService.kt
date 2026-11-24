package com.manc.voice.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.manc.voice.Controller
import com.manc.voice.MainActivity
import com.manc.voice.MancApp
import com.manc.voice.R
import com.manc.voice.audio.AudioEngine
import com.manc.voice.audio.Meters
import com.manc.voice.data.Prefs
import com.manc.voice.shizuku.ShizukuManager
import com.manc.voice.adb.AdbLauncher
import com.manc.voice.bridge.BridgeHub
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Always-on standby service. While "protection" is on it:
 *  - listens for calls from any app (audio mode) and runs the cleaning pipeline
 *    only for the duration of a call (or while forced on for tests/enrollment),
 *  - keeps the shell bridge alive: restarts the built-in helper by itself (or uses
 *    a running Shizuku) and tells the user only when a step really needs them,
 *  - comes back after reboot via BootReceiver.
 * The app never records audio itself; the bridge does.
 */
class VoiceService : Service() {
    private val TAG = "VoiceService"
    private val SR = 16000
    private val handler = Handler(Looper.getMainLooper())
    private var watcher: CallWatcher? = null
    @Volatile private var pipelineRunning = false
    private var worker: Thread? = null
    private var engine: AudioEngine? = null
    private var inCall = false
    private var stopping = false

    private val bridgeRetry = object : Runnable {
        override fun run() {
            ShizukuManager.refresh(applicationContext)
            BridgeHub.refresh()
            val ok = BridgeHub.bridge != null
            // no bridge: restart the built-in helper by ourselves when nothing needs the user
            if (ok) AdbLauncher.evaluate(applicationContext) else AdbLauncher.ensureRunning(applicationContext)
            Controller.setBridgeHint(if (ok) "" else AdbLauncher.hint())
            if (!pipelineRunning) updateNotification()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ShizukuManager.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping = true
                stopPipeline()
                Controller.setRun(Controller.Run.STOPPED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EVALUATE -> { evaluate(); return START_STICKY }
        }
        stopping = false
        startForegroundSafe()
        if (watcher == null) {
            watcher = CallWatcher(this) { active ->
                handler.post { inCall = active; Controller.setInCall(active); evaluate() }
            }.also { it.start() }
        }
        if (!pipelineRunning) Controller.setRun(Controller.Run.STANDBY)
        handler.removeCallbacks(bridgeRetry)
        handler.post(bridgeRetry)
        evaluate()
        return START_STICKY
    }

    private fun evaluate() {
        val want = inCall || Controller.forceActive
        if (want && !pipelineRunning) startPipeline()
        else if (!want && pipelineRunning) stopPipeline()
        updateNotification()
    }

    // ---- notification ----
    private fun statusText(): String = when {
        pipelineRunning -> "Protecting your voice" + (if (inCall) " (in call)" else " (test)")
        BridgeHub.bridge == null -> AdbLauncher.hint().ifEmpty { "Reconnecting…" }
        else -> "Standing by. Starts automatically when a call connects."
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, MancApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText())
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundSafe() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification()) } catch (_: Throwable) {}
    }

    // ---- pipeline ----
    private fun startPipeline() {
        val bridge = BridgeHub.bridge
        if (bridge == null) {
            Controller.setStatus(AdbLauncher.hint().ifEmpty { "Reconnecting…" })
            ShizukuManager.refresh(applicationContext)
            AdbLauncher.ensureRunning(applicationContext)
            handler.postDelayed({ if (!pipelineRunning) evaluate() }, 2000)
            return
        }
        val e = AudioEngine(applicationContext)
        e.config.denoiseEnabled = Controller.config.denoiseEnabled
        e.config.voiceLockEnabled = Controller.config.voiceLockEnabled
        e.config.nearFieldEnabled = Controller.config.nearFieldEnabled
        e.config.hardMute = Controller.config.hardMute
        e.config.strength = Controller.config.strength
        e.config.neuralWet = Controller.config.neuralWet
        e.config.makeupGain = Controller.config.makeupGain
        e.config.bypass = Controller.config.bypass
        Controller.enrolledProfile?.let { e.importProfile(it) }
        Controller.enrolledNeural?.let { e.importNeuralProfile(it) }
        engine = e
        pipelineRunning = true
        Controller.setRun(Controller.Run.STARTING)
        worker = Thread { loop(bridge, e) }.apply { isDaemon = true; start() }
    }

    private fun stopPipeline() {
        pipelineRunning = false
        try { worker?.join(800) } catch (_: Throwable) {}
        worker = null
        engine = null
        Controller.publishMeters(Meters())
        if (!stopping) Controller.setRun(Controller.Run.STANDBY)
    }

    private fun loop(bridge: com.manc.voice.bridge.IVoiceBridge, engine: AudioEngine) {
        var micRead: ParcelFileDescriptor? = null
        try {
            micRead = bridge.startMicCapture(SR) ?: run {
                Controller.setStatus("Microphone capture failed in the bridge.")
                return
            }
            val cleanPipe = ParcelFileDescriptor.createPipe()
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
                Controller.setStatus(when (mode) {
                    Controller.Mode.CELLULAR -> "Cellular path not available on this device (see Cellular Lab report)."
                    Controller.Mode.AUTO -> "Could not install the virtual microphone. See Advanced → Connection diagnostics."
                    else -> "Could not install the virtual microphone for the selected app."
                })
                return
            }

            Controller.setRun(Controller.Run.RUNNING)
            Controller.setStatus(when (mode) {
                Controller.Mode.CELLULAR -> "Cleaning cellular uplink."
                Controller.Mode.AUTO -> "Protecting your voice: ${Controller.sinks.value}."
                else -> "Cleaning your microphone."
            })
            handler.post { updateNotification() }

            val input = FileInputStream(micRead.fileDescriptor)
            val output = FileOutputStream(cleanWrite.fileDescriptor)
            val chunk = SR / 50 // 20 ms
            val inBytes = ByteArray(chunk * 2)
            val inFloat = FloatArray(chunk)
            val outFloat = FloatArray(chunk)
            val outBytes = ByteArray(chunk * 2)

            while (pipelineRunning) {
                var filled = 0
                while (filled < inBytes.size) {
                    val r = input.read(inBytes, filled, inBytes.size - filled)
                    if (r <= 0) { pipelineRunning = false; break }
                    filled += r
                }
                if (!pipelineRunning) break
                val n = filled / 2
                for (i in 0 until n) {
                    val lo = inBytes[i * 2].toInt() and 0xFF
                    val hi = inBytes[i * 2 + 1].toInt()
                    inFloat[i] = (((hi shl 8) or lo).toShort()).toInt() / 32768f
                }
                applyConfig(engine)
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
        } finally {
            try { bridge.stopInjection() } catch (_: Throwable) {}
            try { bridge.stopCellularRedirect() } catch (_: Throwable) {}
            try { bridge.stopMicCapture() } catch (_: Throwable) {}
            try { micRead?.close() } catch (_: Throwable) {}
            pipelineRunning = false
            handler.post {
                Controller.publishMeters(Meters())
                if (!stopping) Controller.setRun(Controller.Run.STANDBY)
                updateNotification()
            }
        }
    }

    private fun applyConfig(engine: AudioEngine) {
        engine.config.denoiseEnabled = Controller.config.denoiseEnabled
        engine.config.voiceLockEnabled = Controller.config.voiceLockEnabled
        engine.config.nearFieldEnabled = Controller.config.nearFieldEnabled
        engine.config.hardMute = Controller.config.hardMute
        engine.config.strength = Controller.config.strength
        engine.config.neuralWet = Controller.config.neuralWet
        engine.config.makeupGain = Controller.config.makeupGain
        engine.config.bypass = Controller.config.bypass
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        stopPipeline()
        watcher?.stop(); watcher = null
        Controller.setRun(Controller.Run.STOPPED)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.manc.voice.STOP"
        const val ACTION_EVALUATE = "com.manc.voice.EVALUATE"
        private const val NOTIF_ID = 1

        /** Turn protection on (standby + auto-activate on calls). */
        fun start(ctx: Context) {
            val i = Intent(ctx, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, VoiceService::class.java).setAction(ACTION_STOP)) }
        /** Re-check whether the pipeline should run (after forceActive changes). */
        fun evaluate(ctx: Context) { try { ctx.startService(Intent(ctx, VoiceService::class.java).setAction(ACTION_EVALUATE)) } catch (_: Exception) {} }
    }
}
