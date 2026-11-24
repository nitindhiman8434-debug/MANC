package com.manc.voice.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.manc.voice.adb.LaunchLogic
import com.manc.voice.server.BinderSender

/**
 * Door through which the shell helper hands the app its bridge Binder. Exported
 * so the shell user can reach it, but it only listens to the shell user (or
 * root): any other caller is refused.
 */
class BridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != BinderSender.METHOD) return null
        if (!LaunchLogic.isTrustedCaller(Binder.getCallingUid())) throw SecurityException("MANC bridge: caller not allowed")
        val binder = extras?.getBinder(BinderSender.KEY_BRIDGE) ?: return null
        BridgeHub.onBuiltInBridge(binder, extras.getInt(BinderSender.KEY_VERSION, 0), extras.getString(BinderSender.KEY_LOG))
        return Bundle().apply { putBinder(BinderSender.KEY_TOKEN, BridgeHub.livenessToken) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

}
