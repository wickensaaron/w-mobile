package com.nuvio.app.core.network

import android.content.Context
import android.content.SharedPreferences

internal actual object WCoreOriginStorage {
    private const val PREFERENCES_NAME = "w_core_connection"
    private const val ORIGIN_KEY = "custom_origin"
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    actual fun loadCustomOrigin(): String? = preferences?.getString(ORIGIN_KEY, null)

    actual fun saveCustomOrigin(origin: String): Boolean =
        preferences?.edit()?.putString(ORIGIN_KEY, origin)?.commit() == true

    actual fun clearCustomOrigin(): Boolean =
        preferences?.edit()?.remove(ORIGIN_KEY)?.commit() == true
}
