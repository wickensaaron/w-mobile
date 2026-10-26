package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsStorage {
    private const val preferencesName = "nuvio_downloads"
    private const val payloadKey = "downloads_payload"

    private var preferences: SharedPreferences? = null
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPreferences(): String? = preferences?.getString(ProfileScopedKey.of("download_preferences"), null)
    actual fun savePreferences(payload: String) { preferences?.edit()?.putString(ProfileScopedKey.of("download_preferences"), payload)?.apply() }
    actual fun availableBytes(): Long? = appContext?.let { runCatching { android.os.StatFs(it.filesDir.path).availableBytes }.getOrNull() }

    actual fun loadPayload(): String? =
        preferences?.getString(ProfileScopedKey.of(payloadKey), null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey), payload)
            ?.apply()
    }
}
