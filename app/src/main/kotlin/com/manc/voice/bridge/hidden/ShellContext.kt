package com.manc.voice.bridge.hidden

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.os.Process

/**
 * Minimal "I am com.android.shell" context used when calling framework audio APIs
 * from the shell-user process (MANC's helper, or a Shizuku user service). Mirrors the well-known scrcpy approach
 * (Apache-2.0); see NOTICE. Without this, Android 14+ rejects the calls because
 * AttributionSource reports an app package the shell UID does not own.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object ShellContext {
    const val PACKAGE_NAME = "com.android.shell"

    private val activityThreadClass: Class<*>
    private val activityThread: Any
    private val bootstrap = OnceGuard()
    @Volatile private var controllerFilled = false

    init {
        // ActivityThread's constructor creates a Handler, which needs a Looper on the
        // thread that runs it. The helper initialises this on its main thread, but a
        // first touch from any other thread (a Binder thread, a worker) must not fail:
        // that would poison this class for the rest of the process.
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepare()
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

    /** Non-fatal notes from the bootstrap, surfaced in the helper log. */
    @Volatile var notes: String = ""
        private set

    private fun note(what: String, e: Throwable) {
        notes += "$what: ${(e.cause ?: e)}\n"
    }

    /**
     * On Samsung devices the system context cannot be created unless the fake
     * ActivityThread has a ConfigurationController (DisplayManagerGlobal asks
     * ActivityThread for its configuration). Must run before anything touches
     * [systemContext]. Same workaround as scrcpy (issue 4467). Idempotent.
     */
    @Synchronized
    private fun ensureConfigurationController() {
        if (controllerFilled) return
        controllerFilled = true
        if (android.os.Build.VERSION.SDK_INT < 31) return
        try {
            val controllerClass = Class.forName("android.app.ConfigurationController")
            val internalClass = Class.forName("android.app.ActivityThreadInternal")
            val ctor = controllerClass.getDeclaredConstructor(internalClass)
            ctor.isAccessible = true
            val controller = ctor.newInstance(activityThread)
            val field = activityThreadClass.getDeclaredField("mConfigurationController")
            field.isAccessible = true
            field.set(activityThread, controller)
        } catch (e: Throwable) { note("configuration controller", e) }
    }

    /**
     * The fake "com.android.shell" context. Building it must NOT call [prepare]:
     * prepare() itself needs this context for the fake Application, and the two
     * calling each other recursed without end on the first device run.
     */
    val context: Context by lazy {
        ensureConfigurationController()
        object : ContextWrapper(systemContext) {
            override fun getPackageName(): String = PACKAGE_NAME
            override fun getOpPackageName(): String = PACKAGE_NAME
            override fun getApplicationContext(): Context = this
            override fun createPackageContext(packageName: String?, flags: Int): Context = this
            override fun getDeviceId(): Int = 0
            override fun getAttributionSource(): android.content.AttributionSource {
                val b = android.content.AttributionSource.Builder(Process.SHELL_UID)
                b.setPackageName(PACKAGE_NAME)
                return b.build()
            }
        }
    }

    /** One-time bootstrap of the fake app environment. Safe to call from anywhere, any number of times. */
    fun prepare() = bootstrap.run {
        ensureConfigurationController()
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
        } catch (e: Throwable) { note("app info", e) }
        try {
            // Give the process a real Application so ActivityThread.currentOpPackageName()
            // and AttributionSource resolve to com.android.shell (Android 14+ reject audio
            // tracks/records whose attribution cannot be resolved). Same approach as scrcpy.
            val app = android.app.Instrumentation.newApplication(android.app.Application::class.java, context)
            val mInitialApplication = activityThreadClass.getDeclaredField("mInitialApplication")
            mInitialApplication.isAccessible = true
            mInitialApplication.set(activityThread, app)
        } catch (e: Throwable) { note("app context", e) }
    }
}
