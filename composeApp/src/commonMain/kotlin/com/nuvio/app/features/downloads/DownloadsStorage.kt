package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPreferences(): String?
    fun savePreferences(payload: String)
    fun availableBytes(): Long?
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
