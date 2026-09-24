package com.nuvio.app.core.network

internal actual object WCoreOriginStorage {
    actual fun loadCustomOrigin(): String? = null
    actual fun saveCustomOrigin(origin: String): Boolean = false
    actual fun clearCustomOrigin(): Boolean = true
}
