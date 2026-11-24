package com.manc.voice.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.manc.voice.bridge.VoiceBridgeService
import com.manc.voice.bridge.hidden.ShellContext
import java.io.File
import java.io.FileOutputStream

/**
 * The shell-user helper (UID 2000). The app starts it once over the phone's own
 * wireless-debugging ADB:
 *
 *   CLASSPATH=<app.apk> setsid app_process /system/bin --nice-name=manc_voice_bridge \
 *       com.manc.voice.server.BridgeServer <pkg> <versionCode> <userId> &
 *
 * It then lives on its own, detached from ADB: it hosts the same
 * [VoiceBridgeService] Binder that used to run inside Shizuku and hands it to
 * the app through the app's exported BridgeProvider, again whenever the app
 * process restarts. Wi-Fi / wireless debugging are only needed for the few
 * seconds it takes to start this process.
 *
 * Touches only framework classes and this app's own bridge classes (no
 * AndroidX), so it loads cleanly under app_process.
 */
object BridgeServer {
    private val logBuf = StringBuilder()
    private val wake = Object()

    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args.getOrNull(0) ?: run { System.err.println("usage: BridgeServer <pkg> <versionCode> <userId>"); return }
        val versionCode = args.getOrNull(1)?.toIntOrNull() ?: 0
        val userId = args.getOrNull(2)?.toIntOrNull() ?: 0
        val apkPath = System.getenv("CLASSPATH").orEmpty()
        log("start pid=${Process.myPid()} uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT} pkg=$pkg v=$versionCode")

        val mainThread = Thread.currentThread()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("uncaught in ${t.name}: ${describe(e)}")
            e.printStackTrace()
            // a failed worker or Binder thread must not take the whole helper down
            if (t === mainThread) System.exit(1)
        }
        try { Looper.prepareMainLooper() } catch (_: Throwable) { }
        escapeAdbCgroup()
        if (!becomeTheOnlyInstance(pkg)) { log("another instance holds the lock; exiting"); return }

        // The Binder must reach the app whatever happens to the audio bootstrap, so the
        // service is built without it and the bootstrap gets a bounded head start.
        val service = try {
            VoiceBridgeService(true)
        } catch (e: Throwable) {
            log("bridge init failed: ${describe(e)}"); e.printStackTrace(); return
        }
        // Delivery must not depend on the audio bootstrap, and the bootstrap must run
        // on this (main, Looper) thread: ActivityThread creates a Handler. So bootstrap
        // here, and let a watchdog start delivery anyway if it has not finished in 4 s.
        val deliveryStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        fun startDelivery() {
            if (deliveryStarted.compareAndSet(false, true)) {
                log("bridge ready; delivering to $pkg (user $userId)")
                Thread({ deliverLoop(pkg, userId, versionCode, apkPath, service) }, "manc-deliver").start()
            }
        }
        Thread({
            try { Thread.sleep(4000) } catch (_: InterruptedException) { }
            if (!deliveryStarted.get()) { log("bootstrap still running after 4 s; delivering anyway"); startDelivery() }
        }, "manc-watchdog").apply { isDaemon = true; start() }

        try {
            ShellContext.prepare()
            if (ShellContext.notes.isNotBlank()) log("bootstrap notes: " + ShellContext.notes.trim().replace('\n', ' '))
            val am = ShellContext.context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            log("system context ok; audio mode=${am.mode}")
        } catch (e: Throwable) {
            log("bootstrap FAILED: ${describe(e)}")
        }
        startDelivery()
        Looper.loop()
    }

    /**
     * Keep the app supplied with our Binder: send it now, and again each time the
     * app process dies and comes back. Exit when the APK we were loaded from is
     * gone (uninstall or update; an updated app starts a fresh helper).
     */
    private fun deliverLoop(pkg: String, userId: Int, versionCode: Int, apkPath: String, service: VoiceBridgeService) {
        var appToken: IBinder? = null
        var misses = 0
        while (true) {
            if (apkPath.isNotEmpty() && !File(apkPath).exists()) { log("apk gone; exiting"); System.exit(0) }
            val alive = appToken?.isBinderAlive == true
            if (!alive) {
                appToken = null
                try {
                    val extras = Bundle()
                    extras.putBinder(BinderSender.KEY_BRIDGE, service)
                    extras.putInt(BinderSender.KEY_VERSION, versionCode)
                    extras.putString(BinderSender.KEY_LOG, synchronized(logBuf) { logBuf.toString() })
                    val reply = BinderSender.send(pkg, userId, extras)
                    val token = reply?.getBinder(BinderSender.KEY_TOKEN)
                    if (token != null) {
                        token.linkToDeath({ synchronized(wake) { wake.notifyAll() } }, 0)
                        appToken = token
                        misses = 0
                        log(com.manc.voice.adb.LaunchLogic.DELIVERED_MARKER)
                    } else {
                        misses++
                        log("provider unreachable (try $misses)")
                    }
                } catch (e: Throwable) {
                    misses++
                    log("deliver failed (try $misses): ${describe(e)}")
                }
            }
            val waitMs = if (appToken != null) 10_000L else minOf(15_000L, 2_000L * (misses.coerceAtLeast(1)))
            synchronized(wake) { try { wake.wait(waitMs) } catch (_: InterruptedException) { } }
        }
    }

    /**
     * adbd's children share its control group, and init kills that whole group
     * when adbd stops (for example when wireless debugging is switched off).
     * Moving ourselves to a root group first is what lets the helper outlive the
     * ADB session; the same step Shizuku's starter performs. Best effort.
     */
    private fun escapeAdbCgroup() {
        val pid = Process.myPid().toString().toByteArray()
        var moved = false
        for (root in arrayOf("/acct", "/dev/cg2_bpf", "/sys/fs/cgroup", "/dev/memcg/apps")) {
            if (moved && root == "/dev/memcg/apps") break
            try {
                FileOutputStream("$root/cgroup.procs").use { it.write(pid) }
                moved = true
                log("cgroup: moved via $root")
            } catch (e: Throwable) {
                log("cgroup: $root no (${e.message})")
            }
        }
        try { log("cgroup now: " + File("/proc/self/cgroup").readText().trim().replace('\n', ' ')) } catch (_: Throwable) { }
    }

    /**
     * One helper per installed app. A newly started helper asks the previous one
     * (if any) to exit by touching its abstract lock socket, then takes the lock.
     */
    private fun becomeTheOnlyInstance(pkg: String): Boolean {
        val name = "manc_voice_bridge.$pkg"
        try {
            LocalSocket().use { s ->
                s.connect(LocalSocketAddress(name))
                s.outputStream.write(1)
                log("asked the previous helper to exit")
            }
        } catch (_: Throwable) { /* nobody there */ }
        for (attempt in 1..20) {
            try {
                val lock = LocalServerSocket(name)
                Thread({
                    try { lock.accept(); log("replaced by a newer helper; exiting") } catch (_: Throwable) { }
                    System.exit(0)
                }, "manc-lock").apply { isDaemon = true; start() }
                return true
            } catch (_: Throwable) {
                try { Thread.sleep(150) } catch (_: InterruptedException) { }
            }
        }
        return false
    }

    /** Reflection wraps the real failure; walk down to it. */
    private fun describe(e: Throwable): String {
        val sb = StringBuilder(e.toString())
        var c = e.cause
        var depth = 0
        while (c != null && depth < 4) { sb.append(" <- ").append(c.toString()); c = c.cause; depth++ }
        return sb.toString()
    }

    private fun log(m: String) {
        System.err.println("BridgeServer: $m")
        synchronized(logBuf) {
            if (logBuf.length < 6000) logBuf.append(m).append('\n')
        }
    }
}
