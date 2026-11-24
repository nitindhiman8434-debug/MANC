package com.manc.voice.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.manc.voice.BuildConfig
import com.manc.voice.bridge.IVoiceBridge
import com.manc.voice.bridge.VoiceBridgeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/** Owns the Shizuku permission + user-service lifecycle and exposes the bridge. */
object ShizukuManager {
    private const val TAG = "ShizukuManager"
    private const val REQ_CODE = 7011

    enum class State { UNAVAILABLE, NEED_PERMISSION, CONNECTING, CONNECTED, ERROR }

    private val _state = MutableStateFlow(State.UNAVAILABLE)
    val state: StateFlow<State> = _state

    @Volatile var bridge: IVoiceBridge? = null
        private set

    private var userArgs: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                bridge = IVoiceBridge.Stub.asInterface(service)
                _state.value = State.CONNECTED
                Log.i(TAG, "bridge connected")
            } else {
                _state.value = State.ERROR
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridge = null
            if (_state.value == State.CONNECTED) _state.value = State.CONNECTING
        }
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) bind() else _state.value = State.NEED_PERMISSION
        }
    }

    fun init(context: Context) {
        try {
            Shizuku.addRequestPermissionResultListener(permListener)
        } catch (_: Throwable) {}
        refresh(context)
    }

    fun refresh(context: Context) {
        if (!Shizuku.pingBinder()) { _state.value = State.UNAVAILABLE; return }
        if (Shizuku.isPreV11()) { _state.value = State.UNAVAILABLE; return }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            if (bridge == null) bind() else _state.value = State.CONNECTED
        } else {
            _state.value = State.NEED_PERMISSION
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) { _state.value = State.NEED_PERMISSION; return }
            Shizuku.requestPermission(REQ_CODE)
        } catch (e: Throwable) { Log.w(TAG, "requestPermission: ${e.message}"); _state.value = State.ERROR }
    }

    private fun bind() {
        _state.value = State.CONNECTING
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, VoiceBridgeService::class.java.name)
            ).daemon(false)
             .processNameSuffix("bridge")
             .debuggable(BuildConfig.DEBUG)
             .version(1)
            userArgs = args
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            Log.e(TAG, "bind failed: ${e.message}")
            _state.value = State.ERROR
        }
    }

    fun unbind() {
        try { userArgs?.let { Shizuku.unbindUserService(it, connection, true) } } catch (_: Throwable) {}
        bridge = null
    }
}
