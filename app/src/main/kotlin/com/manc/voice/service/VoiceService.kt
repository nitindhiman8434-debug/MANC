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
            if (ok) {
                AdbLauncher.evaluate(applicationContext)
                // an older helper keeps working after an app update; swap it when nothing is running
                AdbLauncher.upgradeHelperIfStale(applicationContext, idle = !pipelineRunning && !inCall && !Controller.forceActive)
            } else AdbLauncher.ensureRunning(applicationContext)
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
        try {
            // Audio crosses to the shell bridge as Binder calls only (no pipes between
            // the two processes), ~20 ms per call in each direction.
            if (!bridge.openMic(SR)) {
                Controller.setStatus("Microphone capture failed in the bridge.")
                return
            }

            val mode = Controller.mode.value
            val report: String = when (mode) {
                Controller.Mode.AUTO -> bridge.openSink(SINK_EVERYWHERE, 0, SR)
                Controller.Mode.VOIP -> {
                    val pkg = Controller.targetPackage.value
                    val uid = if (pkg != null) try { packageManager.getPackageUid(pkg, 0) } catch (e: Exception) { -1 } else -1
                    if (uid >= 0) bridge.openSink(SINK_UID, uid, SR) else bridge.openSink(SINK_PRESET, 7, SR)
                }
                Controller.Mode.CELLULAR -> bridge.openSink(SINK_CELLULAR, 0, SR)
            }
            val j = try { JSONObject(report) } catch (e: Exception) { JSONObject() }
            val ok = j.optBoolean("started", false)
            if (mode == Controller.Mode.AUTO) {
                val apps = j.optBoolean("apps", false); val cell = j.optBoolean("cellular", false)
                Controller.setSinks(buildString {
                    if (apps) append("all apps")
                    if (apps && cell) append(" + ")
                    if (cell) append("cellular")
                    if (!apps && !cell) append("none")
                })
            }
            if (mode == Controller.Mode.CELLULAR) Controller.setCellularReport(report)
            if (!ok) {
                val why = j.optString("reason").let { if (it.isNotEmpty()) " ($it)" else "" }
                Controller.setStatus(when (mode) {
                    Controller.Mode.CELLULAR -> "Cellular path not available on this device (see Cellular Lab report)."
                    Controller.Mode.AUTO -> "Could not install the virtual microphone$why. See Advanced → Connection diagnostics."
                    else -> "Could not install the virtual microphone for the selected app$why."
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

            val chunk = SR / 50 // the engine works on exact 20 ms blocks
            val chunkBytes = chunk * 2
            val acc = ByteArray(chunkBytes * 8)
            var accLen = 0
            val inFloat = FloatArray(chunk)
            val outFloat = FloatArray(chunk)
            var emptyReads = 0

            while (pipelineRunning) {
                val mic = bridge.readMic()
                if (mic.isEmpty()) {
                    if (++emptyReads > 50) { Controller.setStatus("The microphone stopped delivering audio."); break }
                    try { Thread.sleep(10) } catch (_: InterruptedException) {}
                    continue
                }
                emptyReads = 0
                val take = minOf(mic.size, acc.size - accLen)
                System.arraycopy(mic, 0, acc, accLen, take)
                accLen += take

                while (accLen >= chunkBytes && pipelineRunning) {
                    for (i in 0 until chunk) {
                        val lo = acc[i * 2].toInt() and 0xFF
                        val hi = acc[i * 2 + 1].toInt()
                        inFloat[i] = (((hi shl 8) or lo).toShort()).toInt() / 32768f
                    }
                    accLen -= chunkBytes
                    System.arraycopy(acc, chunkBytes, acc, 0, accLen)

                    applyConfig(engine)
                    if (Controller.enrollRequested) {
                        Controller.enrollRequested = false
                        Controller.setEnrollResult(Controller.EnrollResult.NONE)
                        engine.beginEnroll()
                    }
                    if (Controller.enrollFinishRequested) {
                        Controller.enrollFinishRequested = false
                        val ok2 = engine.finishEnroll()
                        val prof = engine.exportProfile()
                        Controller.enrolledProfile = prof
                        // a failed re-teach keeps the earlier voiceprint, if there was one
                        Controller.setEnrolled(engine.isEnrolled())
                        Controller.setEnrollResult(if (ok2) Controller.EnrollResult.SAVED else Controller.EnrollResult.TOO_LITTLE_SPEECH)
                        if (ok2) Prefs.saveVoiceprintAsync(applicationContext, prof)
                        engine.exportNeuralProfile()?.let { np -> Controller.enrolledNeural = np; Prefs.saveNeuralVoiceprintAsync(applicationContext, np) }
                    }

                    val produced = engine.process(inFloat, chunk, outFloat)
                    if (produced > 0) {
                        val outBytes = ByteArray(produced * 2)
                        for (i in 0 until produced) {
                            var v = (outFloat[i] * 32767f).toInt()
                            if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                            outBytes[i * 2] = (v and 0xFF).toByte()
                            outBytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        }
                        bridge.writeClean(outBytes)
                    }
                    Controller.publishMeters(engine.meters)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "loop error", e)
            Controller.setStatus(
                if (e is android.os.DeadObjectException) "The bridge stopped. Reconnecting…" else "Audio loop error: ${e.message}"
            )
        } finally {
            try { bridge.stopInjection() } catch (_: Throwable) {}
            try { bridge.stopCellularRedirect() } catch (_: Throwable) {}
            try { bridge.stopMicCapture() } catch (_: Throwable) {}
            pipelineRunning = false
            handler.post {
                Controller.publishMeters(Meters())
                if (!stopping) Controller.setRun(Controller.Run.STANDBY)
                updateNotification()
            }
            // if the call is still on (for example the helper was restarted mid-call), try again shortly
            handler.postDelayed({ if (!stopping && !pipelineRunning) evaluate() }, 3000)
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
        // IVoiceBridge.openSink modes
        private const val SINK_EVERYWHERE = 0
        private const val SINK_UID = 1
        private const val SINK_PRESET = 2
        private const val SINK_CELLULAR = 3
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
