package com.manc.voice.server

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import com.manc.voice.bridge.hidden.ShellContext

/**
 * Shell side: hands a Bundle (carrying the bridge Binder) to the app by calling
 * its exported BridgeProvider. A process started with app_process is not a
 * registered application, so the normal ContentResolver path is unavailable;
 * like scrcpy and Shizuku we ask the activity manager for the provider
 * "externally" and invoke IContentProvider.call directly (Apache-2.0 pattern,
 * see NOTICE).
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object BinderSender {
    const val METHOD = "manc_bridge"
    const val KEY_BRIDGE = "bridge"
    const val KEY_VERSION = "versionCode"
    const val KEY_LOG = "log"
    const val KEY_TOKEN = "token"

    fun authority(pkg: String) = "$pkg.bridge"

    /** Returns the app's reply, or null when the provider could not be reached. */
    fun send(pkg: String, userId: Int, extras: Bundle): Bundle? {
        val authority = authority(pkg)
        val amInterface = Class.forName("android.app.IActivityManager")
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val token: IBinder = Binder()
        val holder = amInterface.getMethod(
            "getContentProviderExternal",
            String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java
        ).invoke(am, authority, userId, token, "manc_bridge") ?: return null
        val provider = holder.javaClass.getField("provider").get(holder) ?: return null
        try {
            return call(provider, authority, extras)
        } finally {
            try {
                amInterface.getMethod(
                    "removeContentProviderExternalAsUser",
                    String::class.java, IBinder::class.java, Int::class.javaPrimitiveType
                ).invoke(am, authority, token, userId)
            } catch (_: Throwable) {
                try {
                    amInterface.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
                        .invoke(am, authority, token)
                } catch (_: Throwable) { }
            }
        }
    }

    /** IContentProvider.call changed shape in API 30 and again in API 31. */
    private fun call(provider: Any, authority: String, extras: Bundle): Bundle? {
        val iface = Class.forName("android.content.IContentProvider")
        var lastError: Throwable? = null
        for (m in iface.methods) {
            if (m.name != "call") continue
            val p = m.parameterTypes
            val args: Array<Any?> = when {
                p.size == 5 && p[0].name == "android.content.AttributionSource" && Build.VERSION.SDK_INT >= 31 ->
                    arrayOf(shellAttribution(), authority, METHOD, null, extras)
                p.size == 6 && p[0] == String::class.java ->
                    arrayOf(ShellContext.PACKAGE_NAME, null, authority, METHOD, null, extras)
                p.size == 5 && p[0] == String::class.java ->
                    arrayOf(ShellContext.PACKAGE_NAME, authority, METHOD, null, extras)
                else -> continue
            }
            try {
                return m.invoke(provider, *args) as Bundle?
            } catch (e: Throwable) {
                lastError = e.cause ?: e
            }
        }
        throw IllegalStateException("IContentProvider.call failed: ${lastError?.message ?: "no matching signature"}", lastError)
    }

    private fun shellAttribution(): Any =
        android.content.AttributionSource.Builder(android.os.Process.SHELL_UID)
            .setPackageName(ShellContext.PACKAGE_NAME)
            .build()
}
