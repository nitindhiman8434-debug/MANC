package com.manc.voice.server

/**
 * Wire contract shared by the in-app client and the shell-UID [BridgeServer].
 *
 * Everything travels over the device's own ADB connection using the
 * `localabstract:` service, which adbd bridges to a LocalServerSocket the
 * server opens in the abstract namespace. That keeps the shell-domain server
 * and the untrusted-app client off a direct cross-SELinux-domain socket (adbd
 * is allowed to reach both), the same mechanism `adb forward localabstract:`
 * uses.
 *
 * Three streams:
 *  - CTL  : the client writes one command line, the server replies with one
 *           JSON status line and keeps it open; closing it tells the server to
 *           shut everything down and exit.
 *  - MIC  : the server writes raw PCM16LE mono at [sampleRate] (the real mic).
 *  - INJ  : the client writes raw PCM16LE mono at [sampleRate] (cleaned voice),
 *           which the server feeds to the audio-policy injector.
 */
object BridgeProtocol {
    const val VERSION = 1

    // Abstract LocalSocket names. The app's package is appended by the caller so
    // two installs (e.g. normal + ".debug") never collide on one device.
    const val CTL = "ctl"
    const val MIC = "mic"
    const val INJ = "inj"

    fun socketName(appId: String, which: String): String = "manc_voice.$appId.$which"

    /** Commands sent on CTL (one line, '\n'-terminated). */
    sealed interface Command {
        /** Inject into every call recorder (all presets) + cellular where allowed. */
        data class Everywhere(val sampleRate: Int) : Command
        /** Inject only for one app, matched by its UID. */
        data class Uid(val uid: Int, val sampleRate: Int) : Command
        /** Inject matched by a single capture preset. */
        data class Preset(val preset: Int, val sampleRate: Int) : Command
        /** Liveness / environment probe; no audio. */
        data object Describe : Command
        /** Tear down and exit. */
        data object Stop : Command
    }

    fun format(c: Command): String = when (c) {
        is Command.Everywhere -> "EVERYWHERE ${c.sampleRate}"
        is Command.Uid -> "UID ${c.uid} ${c.sampleRate}"
        is Command.Preset -> "PRESET ${c.preset} ${c.sampleRate}"
        Command.Describe -> "DESCRIBE"
        Command.Stop -> "STOP"
    }

    /** Parse one CTL line. Returns null on anything unrecognised. */
    fun parse(line: String): Command? {
        val t = line.trim().split(Regex("\\s+"))
        if (t.isEmpty()) return null
        return try {
            when (t[0].uppercase()) {
                "EVERYWHERE" -> Command.Everywhere(t[1].toInt())
                "UID" -> Command.Uid(t[1].toInt(), t[2].toInt())
                "PRESET" -> Command.Preset(t[1].toInt(), t[2].toInt())
                "DESCRIBE" -> Command.Describe
                "STOP" -> Command.Stop
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
