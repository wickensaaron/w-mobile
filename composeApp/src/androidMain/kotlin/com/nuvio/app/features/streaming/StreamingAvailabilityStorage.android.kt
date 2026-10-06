package com.nuvio.app.features.streaming

import android.content.Context
import android.content.SharedPreferences

internal actual object StreamingAvailabilityStorage {
    private var preferences: SharedPreferences? = null
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("streaming_availability", Context.MODE_PRIVATE)
    }
    actual fun loadConfig(): String? = preferences?.getString("config", null)
    actual fun saveConfig(value: String) {
        preferences?.edit()?.putString("config", value)?.apply()
    }
}
