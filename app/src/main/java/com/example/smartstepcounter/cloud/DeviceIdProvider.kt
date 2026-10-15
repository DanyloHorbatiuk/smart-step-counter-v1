package com.example.smartstepcounter.cloud

import android.content.Context
import java.util.UUID

/** A random UUID generated on first launch and kept in SharedPreferences. */
object DeviceIdProvider {
    private const val PREFS = "cloud_prefs"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}
