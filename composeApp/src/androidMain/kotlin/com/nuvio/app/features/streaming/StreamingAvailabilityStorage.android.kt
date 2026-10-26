package com.nuvio.app.features.streaming

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.profiles.ProfileRepository

internal actual object StreamingAvailabilityStorage {
    private var preferences: SharedPreferences? = null
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("streaming_availability", Context.MODE_PRIVATE)
    }
    actual fun loadConfig(): String? {
        val store = preferences ?: return null
        val scopedKey = ProfileScopedKey.of("config")
        store.getString(scopedKey, null)?.let { return it }
        // The old unscoped preference belongs to the primary profile only.
        if (ProfileRepository.activeProfileId != 1) return null
        return store.getString("config", null)?.also {
            store.edit().putString(scopedKey, it).remove("config").apply()
        }
    }
    actual fun saveConfig(value: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of("config"), value)?.apply()
    }
}
