package com.syncsaves.app.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value.trim().uppercase()).apply()

    var pcHost: String
        get() = prefs.getString(KEY_PC_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PC_HOST, value.trim()).apply()

    var customScanRoot: String
        get() = prefs.getString(KEY_CUSTOM_ROOT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_ROOT, value).apply()

    companion object {
        private const val PREFS_NAME = "syncsaves"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PC_HOST = "pc_host"
        private const val KEY_CUSTOM_ROOT = "custom_scan_root"
    }
}
