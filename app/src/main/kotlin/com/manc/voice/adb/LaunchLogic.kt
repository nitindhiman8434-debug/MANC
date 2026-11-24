package com.manc.voice.adb

/**
 * Pure decision logic for the self-pairing bridge, kept free of Android types so
 * it is unit-tested on the JVM.
 */
object LaunchLogic {

    enum class Step {
        /** The shell helper is running and its Binder is in hand. */
        CONNECTED,
        /** A pair / launch attempt is in flight. */
        WORKING,
        /** Developer options are off; wireless debugging cannot be reached. */
        NEED_DEV_OPTIONS,
        /** Android only offers wireless debugging on Wi-Fi. */
        NEED_WIFI,
        /** The user must switch wireless debugging on (we cannot do it ourselves yet). */
        NEED_WIRELESS_DEBUGGING,
        /** Wireless debugging is on; waiting for the one-time pairing code. */
        NEED_PAIRING,
        /** Everything is in place; the helper can be (re)started without the user. */
        READY_TO_LAUNCH
    }

    data class Inputs(
        val connected: Boolean,
        val busy: Boolean,
        val paired: Boolean,
        val devOptions: Boolean,
        val wifi: Boolean,
        val wirelessDebugging: Boolean,
        /** WRITE_SECURE_SETTINGS held, so the app can flip wireless debugging itself. */
        val canToggle: Boolean
    )

    fun decide(i: Inputs): Step = when {
        i.connected -> Step.CONNECTED
        i.busy -> Step.WORKING
        !i.devOptions && !i.wirelessDebugging -> Step.NEED_DEV_OPTIONS
        !i.wifi -> Step.NEED_WIFI
        !i.paired -> if (i.wirelessDebugging) Step.NEED_PAIRING else Step.NEED_WIRELESS_DEBUGGING
        i.wirelessDebugging || i.canToggle -> Step.READY_TO_LAUNCH
        else -> Step.NEED_WIRELESS_DEBUGGING
    }

    /** Seconds to wait before the next automatic relaunch after [failures] misses in a row. */
    fun retryGapSeconds(failures: Int): Int = when {
        failures < 3 -> 20
        failures >= 7 -> 300
        else -> minOf(300, 20 shl (failures - 2))
    }

    /** The bridge door only opens for the shell user (2000) or root (0), in any user profile. */
    fun isTrustedCaller(uid: Int): Boolean {
        val appId = uid % 100000
        return appId == 2000 || appId == 0
    }

    const val DONE_MARKER = "MANC_DONE"
    const val SERVER_CLASS = "com.manc.voice.server.BridgeServer"
    const val NICE_NAME = "manc_voice_bridge"

    private val SAFE_PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun isSafePackage(pkg: String): Boolean = SAFE_PACKAGE.matches(pkg)

    /**
     * First contact only: give the app WRITE_SECURE_SETTINGS in a short session of
     * its own, so that it can apply the keep-alive settings (which may restart
     * adbd) before the helper is started rather than underneath it.
     */
    fun buildGrantScript(pkg: String): String {
        require(isSafePackage(pkg)) { "unsafe package name" }
        return listOf(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>&1",
            "echo granted",
            "echo $DONE_MARKER"
        ).joinToString("\n")
    }

    /** The helper prints this once the app has received its Binder. */
    const val DELIVERED_MARKER = "binder delivered"
    const val PING_METHOD = "manc_ping"

    /**
     * The one shell command run over ADB as the shell user. It lets the app flip
     * wireless debugging by itself from now on, exempts it from battery
     * optimisation, and starts the helper detached from the ADB session so it
     * keeps running after we disconnect. It then waits (up to 15 s, with the ADB
     * session still open) until the helper reports delivery, echoes the helper's
     * log, and when delivery did not happen adds what is needed to see why:
     * surviving processes, whether the shell can reach the app's provider at all,
     * and the relevant crash / SELinux lines from logcat.
     */
    fun buildLaunchScript(pkg: String, versionCode: Int, userId: Int): String {
        require(isSafePackage(pkg)) { "unsafe package name" }
        require(versionCode >= 0 && userId >= 0)
        val log = "/data/local/tmp/manc_bridge_$pkg.log"
        return listOf(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>&1",
            "cmd deviceidle whitelist +$pkg >/dev/null 2>&1",
            "APK=\$(pm path $pkg | head -n 1 | cut -d: -f2)",
            "rm -f $log",
            "S=; command -v setsid >/dev/null 2>&1 && S=setsid",
            "CLASSPATH=\$APK \$S app_process /system/bin --nice-name=$NICE_NAME $SERVER_CLASS $pkg $versionCode $userId >$log 2>&1 </dev/null &",
            "i=0",
            "while [ \$i -lt 30 ]; do grep -q \"$DELIVERED_MARKER\" $log 2>/dev/null && break; sleep 0.5; i=\$((i+1)); done",
            "echo \"apk=\$APK setsid=[\$S] waited=\$i\"",
            "cat $log 2>&1",
            "if ! grep -q \"$DELIVERED_MARKER\" $log 2>/dev/null; then",
            "echo \"--- processes ---\"",
            "ps -A 2>/dev/null | grep -E \"manc_voice|app_process\"",
            "echo \"--- provider ping ---\"",
            "content call --uri content://$pkg.bridge --method $PING_METHOD 2>&1",
            "echo \"--- logcat ---\"",
            // stack frames are dropped: a deep trace would otherwise crowd out the message lines
            "logcat -d -t 2000 2>/dev/null | grep -E \"BridgeServer|manc_voice|AndroidRuntime|avc: +denied.*shell\" | grep -vE \"[[:space:]]at [a-zA-Z]\" | tail -n 40",
            "fi",
            "echo $DONE_MARKER"
        ).joinToString("\n") // newlines, not ';' : "cmd &; next" is a shell syntax error
    }
}
