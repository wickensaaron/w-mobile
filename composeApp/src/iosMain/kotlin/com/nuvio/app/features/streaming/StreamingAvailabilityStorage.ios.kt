package com.nuvio.app.features.streaming

import platform.Foundation.NSUserDefaults

internal actual object StreamingAvailabilityStorage {
    private const val key = "streaming_availability_config"
    actual fun loadConfig(): String? = NSUserDefaults.standardUserDefaults.stringForKey(key)
    actual fun saveConfig(value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = key)
    }
}
