package com.manc.voice.bridge.hidden

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.os.Process

/**
 * Minimal "I am com.android.shell" context used when calling framework audio APIs
 * from the Shizuku shell process. Mirrors the well-known scrcpy approach
 * (Apache-2.0); see NOTICE. Without this, Android 14+ rejects the calls because
 * AttributionSource reports an app package the shell UID does not own.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object ShellContext {
    const val PACKAGE_NAME = "com.android.shell"

    private val activityThreadClass: Class<*>
    private val activityThread: Any
    @Volatile private var prepared = false

    init {
        activityThreadClass = Class.forName("android.app.ActivityThread")
        val ctor = activityThreadClass.getDeclaredConstructor()
        ctor.isAccessible = true
        activityThread = ctor.newInstance()
        val sCurrent = activityThreadClass.getDeclaredField("sCurrentActivityThread")
        sCurrent.isAccessible = true
        sCurrent.set(null, activityThread)
        val mSystemThread = activityThreadClass.getDeclaredField("mSystemThread")
        mSystemThread.isAccessible = true
        mSystemThread.setBoolean(activityThread, true)
    }

    private val systemContext: Context by lazy {
        val m = activityThreadClass.getDeclaredMethod("getSystemContext")
        m.isAccessible = true
        m.invoke(activityThread) as Context
    }

    fun prepare() {
        if (prepared) return
        try {
            // fill app info so AttributionSource uses com.android.shell
            val appBindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val appBindCtor = appBindDataClass.getDeclaredConstructor()
            appBindCtor.isAccessible = true
            val appBindData = appBindCtor.newInstance()
            val appInfo = android.content.pm.ApplicationInfo().apply { packageName = PACKAGE_NAME }
            val appInfoField = appBindDataClass.getDeclaredField("appInfo")
            appInfoField.isAccessible = true
            appInfoField.set(appBindData, appInfo)
            val mBound = activityThreadClass.getDeclaredField("mBoundApplication")
            mBound.isAccessible = true
            mBound.set(activityThread, appBindData)
        } catch (_: Throwable) { }
        prepared = true
    }

    val context: Context by lazy {
        prepare()
        object : ContextWrapper(systemContext) {
            override fun getPackageName(): String = PACKAGE_NAME
            override fun getOpPackageName(): String = PACKAGE_NAME
            override fun getApplicationContext(): Context = this
            override fun getAttributionSource(): android.content.AttributionSource {
                val b = android.content.AttributionSource.Builder(Process.SHELL_UID)
                b.setPackageName(PACKAGE_NAME)
                return b.build()
            }
        }
    }
}
