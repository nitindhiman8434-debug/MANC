package com.manc.voice.bridge.hidden

import android.util.Log

/**
 * Log for code running in the shell-user process. Besides logcat (which the app
 * cannot read) it keeps the last lines in memory, so the app can fetch them over
 * the bridge and show the real reason when the microphone or the virtual mic fails.
 */
object BridgeLog {
    private const val MAX = 160
    private val lines = ArrayDeque<String>()

    @Synchronized
    private fun add(level: Char, tag: String, msg: String) {
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast("$level $tag: $msg")
    }

    fun i(tag: String, msg: String) { add('I', tag, msg); try { Log.i(tag, msg) } catch (_: Throwable) { } }
    fun w(tag: String, msg: String) { add('W', tag, msg); try { Log.w(tag, msg) } catch (_: Throwable) { } }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        add('E', tag, if (t != null) "$msg: ${describe(t)}" else msg)
        try { if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg) } catch (_: Throwable) { }
    }

    /** Reflection wraps the real failure; walk down to it. */
    fun describe(e: Throwable): String {
        val sb = StringBuilder(e.toString())
        var c = e.cause
        var depth = 0
        while (c != null && depth < 4) { sb.append(" <- ").append(c.toString()); c = c.cause; depth++ }
        return sb.toString()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
