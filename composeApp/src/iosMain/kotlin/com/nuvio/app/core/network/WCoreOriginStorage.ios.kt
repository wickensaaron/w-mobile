package com.nuvio.app.core.network

import platform.Foundation.NSUserDefaults

internal actual object WCoreOriginStorage {
    private const val ORIGIN_KEY = "w_core_custom_origin"

    actual fun loadCustomOrigin(): String? = NSUserDefaults.standardUserDefaults.stringForKey(ORIGIN_KEY)

    actual fun saveCustomOrigin(origin: String): Boolean {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setObject(origin, forKey = ORIGIN_KEY)
        return defaults.synchronize()
    }

    actual fun clearCustomOrigin(): Boolean {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.removeObjectForKey(ORIGIN_KEY)
        return defaults.synchronize()
    }
}
