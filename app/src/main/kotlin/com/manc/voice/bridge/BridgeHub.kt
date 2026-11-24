package com.manc.voice.bridge

import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import com.manc.voice.shizuku.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one place the app asks "do we have a shell bridge, and which one?".
 * The built-in helper (started over the phone's own ADB) is preferred; a running
 * Shizuku is used automatically when it is the only one available.
 */
object BridgeHub {
    enum class Source { NONE, BUILT_IN, SHIZUKU }

    /** Handed to the helper so it notices when this app process dies. */
    val livenessToken: IBinder = Binder()

    @Volatile private var builtIn: IVoiceBridge? = null
    @Volatile var serverVersion: Int = 0; private set
    @Volatile var serverLog: String = ""; private set
    /** elapsedRealtime of the last time the built-in helper died, 0 if never. */
    @Volatile var builtInLostAt: Long = 0; private set

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    val bridge: IVoiceBridge?
        get() {
            val b = builtIn
            if (b != null && b.asBinder().isBinderAlive) return b
            return ShizukuManager.bridge
        }

    val source: Source
        get() {
            val b = builtIn
            return when {
                b != null && b.asBinder().isBinderAlive -> Source.BUILT_IN
                ShizukuManager.bridge != null -> Source.SHIZUKU
                else -> Source.NONE
            }
        }

    val builtInAlive: Boolean get() = builtIn?.asBinder()?.isBinderAlive == true

    /** Called by BridgeProvider when the helper delivers its Binder. */
    fun onBuiltInBridge(binder: IBinder, versionCode: Int, log: String?) {
        val iface = IVoiceBridge.Stub.asInterface(binder)
        try {
            binder.linkToDeath({
                if (builtIn?.asBinder() === binder) {
                    builtIn = null
                    builtInLostAt = SystemClock.elapsedRealtime()
                }
                refresh()
            }, 0)
        } catch (_: Throwable) {
            return // already dead
        }
        builtIn = iface
        serverVersion = versionCode
        serverLog = log.orEmpty()
        refresh()
    }

    fun refresh() { _connected.value = bridge != null }
}
