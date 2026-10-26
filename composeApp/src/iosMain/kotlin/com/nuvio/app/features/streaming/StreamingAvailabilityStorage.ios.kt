package com.nuvio.app.features.streaming

import platform.Foundation.NSUserDefaults
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.profiles.ProfileRepository

internal actual object StreamingAvailabilityStorage {
    private const val key = "streaming_availability_config"
    actual fun loadConfig(): String? {
        val store = NSUserDefaults.standardUserDefaults
        val scopedKey = ProfileScopedKey.of(key)
        store.stringForKey(scopedKey)?.let { return it }
        if (ProfileRepository.activeProfileId != 1) return null
        return store.stringForKey(key)?.also {
            store.setObject(it, forKey = scopedKey)
            store.removeObjectForKey(key)
        }
    }
    actual fun saveConfig(value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = ProfileScopedKey.of(key))
    }
}
