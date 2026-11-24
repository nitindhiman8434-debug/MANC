package com.manc.voice.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.manc.voice.BuildConfig
import com.manc.voice.adb.LaunchLogic.Step
import com.manc.voice.bridge.BridgeHub
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Starts (and restarts) the shell helper without any companion app. ADB is used
 * only as a launcher: pair once with the phone's own wireless debugging, then
 * whenever the helper is not running connect, run one shell command, disconnect.
 * After the first launch the app holds WRITE_SECURE_SETTINGS, so after a reboot
 * it can switch wireless debugging on and relaunch by itself.
 */
object AdbLauncher {
    private const val TAG = "AdbLauncher"
    private const val PREFS = "manc_adb"
    private const val KEY_PAIRED = "paired"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "manc-adb").apply { isDaemon = true } }
    @Volatile private var busy = false
    @Volatile private var failures = 0
    @Volatile private var lastAttempt = 0L
    @Volatile private var wdReadable = true

    private val _step = MutableStateFlow(Step.NEED_DEV_OPTIONS)
    val step: StateFlow<Step> = _step
    private val _error = MutableStateFlow("")
    /** Last failure in plain words, empty when the last attempt worked. */
    val error: StateFlow<String> = _error
    private val _pairingPort = MutableStateFlow(-1)
    /** Port of the "Pair device with pairing code" dialog while it is open, else -1. */
    val pairingPort: StateFlow<Int> = _pairingPort
    /** Raw output of the last launch, for the diagnostics panel. */
    @Volatile var lastLog: String = ""; private set

    private var pairingWatch: AdbMdns? = null

    // ---------------------------------------------------------------- state

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isPaired(ctx: Context) = prefs(ctx).getBoolean(KEY_PAIRED, false)
    private fun setPaired(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_PAIRED, v).apply()

    fun canToggle(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun devOptionsOn(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    } catch (_: Throwable) { true }

    private fun wirelessDebuggingOn(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    } catch (_: Throwable) {
        wdReadable = false; false
    }

    private fun wifiConnected(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    } catch (_: Throwable) { true }

    /** Recompute where we stand; cheap, safe to call often. */
    fun evaluate(ctx: Context): Step {
        val wd = wirelessDebuggingOn(ctx) || _pairingPort.value > 0 || !wdReadable
        val s = LaunchLogic.decide(
            LaunchLogic.Inputs(
                connected = BridgeHub.bridge != null,
                busy = busy,
                paired = isPaired(ctx),
                devOptions = devOptionsOn(ctx),
                wifi = wifiConnected(ctx),
                wirelessDebugging = wd,
                canToggle = canToggle(ctx)
            )
        )
        _step.value = s
        if (s == Step.CONNECTED) { _error.value = ""; stopPairingWatch() }
        return s
    }

    /** One short line for the notification / Home screen while not connected. */
    fun hint(step: Step = _step.value): String = when (step) {
        Step.CONNECTED -> ""
        Step.WORKING, Step.READY_TO_LAUNCH -> "Reconnecting…"
        Step.NEED_WIFI -> "Connect to Wi-Fi once to resume protection"
        else -> "Open MANC to finish setup"
    }

    // ------------------------------------------------------------- launching

    /** Start the helper if it is not running and nothing needs the user. */
    fun ensureRunning(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return // no wireless debugging before Android 11
        if (evaluate(app) != Step.READY_TO_LAUNCH) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastAttempt != 0L && now - lastAttempt < LaunchLogic.retryGapSeconds(failures) * 1000L) return
        lastAttempt = now
        busy = true
        _step.value = Step.WORKING
        exec.execute {
            try { launch(app, firstRun = false) } catch (e: Throwable) { fail("Unexpected error: ${e.message ?: e}") }
            finally { busy = false; evaluate(app) }
        }
    }

    /** One-time pairing with the 6-digit code from Settings, then the first launch. */
    fun pair(ctx: Context, code: String) {
        val app = ctx.applicationContext
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) {
            _error.value = "The pairing code has 6 digits."
            PairingNotifier.askForCode(app, retry = true)
            return
        }
        if (busy) return
        busy = true
        _step.value = Step.WORKING
        _error.value = ""
        exec.execute {
            try {
                if (doPair(app, digits)) {
                    setPaired(app, true)
                    PairingNotifier.status(app, "Paired. Starting protection…")
                    launch(app, firstRun = true)
                    if (BridgeHub.builtInAlive) PairingNotifier.status(app, "Connected. You can go back to MANC.", done = true)
                    else PairingNotifier.status(app, "Paired, but the helper did not start. Open MANC.", done = true)
                } else {
                    fail("Pairing failed. Open “Pair device with pairing code” again and type the new code.")
                    PairingNotifier.askForCode(app, retry = true)
                }
            } catch (e: Throwable) {
                fail("Pairing error: ${e.message ?: e}")
            } finally { busy = false; evaluate(app) }
        }
    }

    private fun doPair(ctx: Context, code: String): Boolean {
        val mgr = AdbManager.getInstance(ctx)
        val host = AdbManager.hostAddress(ctx)
        val known = _pairingPort.value
        val ports = if (known > 0) listOf(known) else scanOpenLoopbackPorts()
        for (p in ports) {
            try {
                if (mgr.pair(host, p, code)) return true
            } catch (e: Throwable) {
                Log.w(TAG, "pair on $p: ${e.message}")
            }
        }
        return false
    }

    private fun usbDebuggingOn(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    } catch (_: Throwable) { false }

    /**
     * What keeps the helper alive between launches (the same three settings
     * Shizuku applies):
     *  - wireless debugging on, so the app can reach adbd now;
     *  - USB debugging on: Android stops adbd, and every process it started, the
     *    moment neither debugging switch is on. Wireless debugging switches itself
     *    off whenever Wi-Fi drops, so without this the helper would die as soon
     *    as the phone leaves Wi-Fi. A computer still needs on-screen approval;
     *  - no expiry for the pairing (Android otherwise forgets it after 7 days).
     * Returns true when USB debugging was just switched on (adbd may restart).
     */
    private fun applyKeepAlive(ctx: Context): Boolean {
        if (!canToggle(ctx)) return false
        val cr = ctx.contentResolver
        var usbJustEnabled = false
        try { if (!wirelessDebuggingOn(ctx)) Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1) } catch (e: Throwable) { Log.w(TAG, "enable wd: ${e.message}") }
        try {
            if (!usbDebuggingOn(ctx)) { Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1); usbJustEnabled = true }
        } catch (e: Throwable) { Log.w(TAG, "enable usb debugging: ${e.message}") }
        try { Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L) } catch (e: Throwable) { Log.w(TAG, "pairing expiry: ${e.message}") }
        return usbJustEnabled
    }

    private fun launch(ctx: Context, firstRun: Boolean) {
        if (!canToggle(ctx)) {
            // First contact: a short session only to grant the app the permission it
            // needs for the keep-alive settings. They can restart adbd, so they must be
            // in place before the helper starts, not applied underneath it.
            val mgr = AdbManager.getInstance(ctx)
            if (!connect(ctx, mgr)) { failures++; return }
            val out = try { runShell(mgr, LaunchLogic.buildGrantScript(ctx.packageName), 12_000) } catch (e: Throwable) { "shell error: ${e.message ?: e}" }
            try { mgr.disconnect() } catch (_: Throwable) { }
            lastLog = "grant: " + out.trim()
            sleep(500)
        }
        if (applyKeepAlive(ctx)) sleep(3000) // let adbd settle if it restarted
        if (startHelper(ctx)) { failures = 0; _error.value = "" } else failures++
    }

    /**
     * Connect, run the launch command and stay connected while it waits for the
     * helper to hand over its Binder; then disconnect.
     */
    private fun startHelper(ctx: Context): Boolean {
        val mgr = AdbManager.getInstance(ctx)
        if (!connect(ctx, mgr)) return false
        val userId = Process.myUid() / 100000
        val script = LaunchLogic.buildLaunchScript(ctx.packageName, BuildConfig.VERSION_CODE, userId)
        val out = try { runShell(mgr, script, 45_000) } catch (e: Throwable) { "shell error: ${e.message ?: e}" }
        lastLog = (if (lastLog.startsWith("grant: ")) lastLog.lineSequence().first() + "\n" else "") + out
        try { mgr.disconnect() } catch (_: Throwable) { }
        val deadline = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (BridgeHub.builtInAlive) { BridgeHub.refresh(); return true }
            sleep(250)
        }
        fail(
            if (out.contains(LaunchLogic.DELIVERED_MARKER)) "The helper started but stopped again. Tap “Details” and send the report."
            else "The helper did not connect. Tap “Details” and send the report."
        )
        return false
    }

    private fun connect(ctx: Context, mgr: AdbManager): Boolean {
        try { mgr.disconnect() } catch (_: Throwable) { }
        mgr.setTimeout(8, TimeUnit.SECONDS)
        // 1) the daemon announces its port over mDNS
        for (attempt in 1..2) {
            try {
                if (mgr.connectTls(ctx, 7000)) return true
            } catch (e: AdbPairingRequiredException) {
                return unpaired(ctx)
            } catch (e: Throwable) {
                Log.w(TAG, "mdns connect: ${e.message}")
            }
        }
        // 2) fall back to finding the port ourselves on the loopback interface
        val host = AdbManager.hostAddress(ctx)
        for (p in scanOpenLoopbackPorts().take(6)) {
            try { mgr.disconnect() } catch (_: Throwable) { }
            try {
                if (mgr.connect(host, p)) return true
            } catch (e: AdbPairingRequiredException) {
                return unpaired(ctx)
            } catch (e: Throwable) {
                Log.w(TAG, "connect $p: ${e.message}")
            }
        }
        fail(
            if (wirelessDebuggingOn(ctx)) "Could not reach wireless debugging. If Android asks to allow it on this Wi-Fi network, tap Allow."
            else "Wireless debugging is off."
        )
        return false
    }

    private fun unpaired(ctx: Context): Boolean {
        setPaired(ctx, false)
        fail("This phone no longer accepts the earlier pairing. Pair once more.")
        return false
    }

    private fun runShell(mgr: AdbManager, script: String, timeoutMs: Long): String {
        val stream = mgr.openStream("shell:$script")
        val sb = StringBuffer()
        val reader = Thread({
            try {
                stream.openInputStream().bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.trim() == LaunchLogic.DONE_MARKER) break
                        if (sb.length < 14000) sb.append(line).append('\n')
                    }
                }
            } catch (e: Throwable) { sb.append("[read end: ").append(e.message).append("]\n") }
        }, "manc-adb-read").apply { isDaemon = true; start() }
        reader.join(timeoutMs)
        try { stream.close() } catch (_: Throwable) { }
        return sb.toString()
    }

    /**
     * adbd listens on a random port from the kernel's ephemeral range. Refused
     * loopback connects return at once, so sweeping the range takes about a second.
     */
    private fun scanOpenLoopbackPorts(): List<Int> {
        val open = Collections.synchronizedList(ArrayList<Int>())
        val next = AtomicInteger(32768)
        val workers = (1..32).map {
            Thread {
                while (true) {
                    val p = next.getAndIncrement()
                    if (p > 60999) break
                    try { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", p), 40); open.add(p) } } catch (_: Throwable) { }
                }
            }.apply { isDaemon = true; start() }
        }
        workers.forEach { try { it.join(6000) } catch (_: InterruptedException) { } }
        return synchronized(open) { open.sorted() }
    }

    private fun fail(msg: String) { _error.value = msg; Log.w(TAG, msg) }
    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { } }

    // --------------------------------------------------------------- pairing

    /** Watch for Settings' pairing dialog so its port is known the moment it opens. */
    @Synchronized
    fun startPairingWatch(ctx: Context) {
        if (pairingWatch != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = ctx.applicationContext
        pairingWatch = try {
            AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
                _pairingPort.value = port
                if (port > 0 && !isPaired(app) && !busy) PairingNotifier.askForCode(app, retry = false)
                evaluate(app)
            }.also { it.start() }
        } catch (e: Throwable) { Log.w(TAG, "pairing watch: ${e.message}"); null }
    }

    @Synchronized
    fun stopPairingWatch() {
        try { pairingWatch?.stop() } catch (_: Throwable) { }
        pairingWatch = null
        _pairingPort.value = -1
    }

    /** Text block for Home → Advanced → Connection diagnostics. */
    fun diagnostics(ctx: Context): String = buildString {
        append("Bridge: ").append(BridgeHub.source)
        if (BridgeHub.source == BridgeHub.Source.BUILT_IN) append(" (helper build ").append(BridgeHub.serverVersion).append(')')
        append('\n')
        append("State: ").append(_step.value).append('\n')
        append("Build ").append(BuildConfig.VERSION_CODE).append(", paired: ").append(isPaired(ctx))
            .append(", self-toggle: ").append(canToggle(ctx))
            .append(", wireless debugging: ").append(wirelessDebuggingOn(ctx))
            .append(", USB debugging: ").append(usbDebuggingOn(ctx)).append('\n')
        append("Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append("), ")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        if (_error.value.isNotEmpty()) append("Last error: ").append(_error.value).append('\n')
        if (lastLog.isNotBlank()) append("--- last launch ---\n").append(lastLog.trim()).append('\n')
        if (BridgeHub.serverLog.isNotBlank()) append("--- helper ---\n").append(BridgeHub.serverLog.trim()).append('\n')
        val status = com.manc.voice.Controller.status.value
        if (status.isNotBlank()) append("Status: ").append(status).append('\n')
        // what the bridge itself saw (microphone, virtual mic); fetched live over the Binder
        try {
            BridgeHub.bridge?.describeEnvironment()?.let { raw ->
                val j = org.json.JSONObject(raw)
                append("--- bridge (uid ").append(j.optInt("uid")).append(") ---\n")
                j.optString("initError").takeIf { it.isNotEmpty() }?.let { append("bootstrap error: ").append(it).append('\n') }
                j.optString("notes").takeIf { it.isNotEmpty() }?.let { append("bootstrap notes: ").append(it).append('\n') }
                j.optString("log").takeIf { it.isNotEmpty() }?.let { append(it) }
            }
        } catch (e: Throwable) { append("bridge query failed: ").append(e.toString()) }
    }
}
