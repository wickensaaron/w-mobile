package com.nuvio.app.core.network

/** Only the server address is persisted; neither Supabase nor Core tokens belong here. */
internal expect object WCoreOriginStorage {
    fun loadCustomOrigin(): String?
    fun saveCustomOrigin(origin: String): Boolean
    fun clearCustomOrigin(): Boolean
}
