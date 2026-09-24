package com.nuvio.app.features.livetv

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

actual object LiveTvStorage {
    private const val preferencesName = "nuvio_live_tv"
    private const val playlistUrlKey = "playlist_url"
    private const val playlistsBlobKey = "playlists_blob"
    private const val favoriteChannelIdsBlobKey = "favorite_channel_ids_blob"
    private const val lastWatchedChannelIdKey = "last_watched_channel_id"
    private const val navigationEnabledKey = "navigation_enabled"
    private const val stalkerSettingsKey = "stalker_settings"
    private const val xtreamSettingsKey = "xtream_settings"
    private const val guideUrlKey = "guide_url"
    private const val secretsPurgedKey = "w_live_tv_plaintext_purged_v1"

    private var preferences: SharedPreferences? = null
    private val sessionText = mutableMapOf<String, String>()
    private val sessionStalker = mutableMapOf<String, LiveTvStalkerSettings>()
    private val sessionXtream = mutableMapOf<String, LiveTvXtreamSettings>()

    fun initialize(context: Context) {
        val stored = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        if (!stored.getBoolean(secretsPurgedKey, false)) {
            // Earlier local builds used this file for credential-bearing source data.
            check(stored.edit().clear().putBoolean(secretsPurgedKey, true).commit()) {
                "Could not clear prior Live TV source storage."
            }
        }
        preferences = stored
    }

    actual fun loadPlaylistUrl(): String? =
        sessionText[ProfileScopedKey.of(playlistUrlKey)]

    actual fun savePlaylistUrl(url: String) {
        sessionText[ProfileScopedKey.of(playlistUrlKey)] = url
    }

    actual fun loadPlaylistsBlob(): String? =
        sessionText[ProfileScopedKey.of(playlistsBlobKey)]

    actual fun savePlaylistsBlob(blob: String) {
        sessionText[ProfileScopedKey.of(playlistsBlobKey)] = blob
    }

    actual fun loadFavoriteChannelIdsBlob(): String? =
        preferences?.getString(ProfileScopedKey.of(favoriteChannelIdsBlobKey), null)

    actual fun saveFavoriteChannelIdsBlob(blob: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(favoriteChannelIdsBlobKey), blob)
            ?.apply()
    }

    actual fun loadLastWatchedChannelId(): String? =
        preferences?.getString(ProfileScopedKey.of(lastWatchedChannelIdKey), null)

    actual fun saveLastWatchedChannelId(channelId: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(lastWatchedChannelIdKey), channelId)
            ?.apply()
    }

    actual fun loadNavigationEnabled(): Boolean? {
        val preferences = preferences ?: return null
        val key = ProfileScopedKey.of(navigationEnabledKey)
        return if (preferences.contains(key)) preferences.getBoolean(key, true) else null
    }

    actual fun saveNavigationEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(navigationEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadStalkerSettings(): LiveTvStalkerSettings =
        sessionStalker[ProfileScopedKey.of(stalkerSettingsKey)] ?: LiveTvStalkerSettings()

    actual fun saveStalkerSettings(settings: LiveTvStalkerSettings) {
        sessionStalker[ProfileScopedKey.of(stalkerSettingsKey)] = settings
    }

    actual fun loadXtreamSettings(): LiveTvXtreamSettings =
        sessionXtream[ProfileScopedKey.of(xtreamSettingsKey)] ?: LiveTvXtreamSettings()

    actual fun saveXtreamSettings(settings: LiveTvXtreamSettings) {
        sessionXtream[ProfileScopedKey.of(xtreamSettingsKey)] = settings
    }

    actual fun loadGuideUrl(): String? = sessionText[ProfileScopedKey.of(guideUrlKey)]

    actual fun saveGuideUrl(url: String) {
        sessionText[ProfileScopedKey.of(guideUrlKey)] = url
    }

    actual fun publishNavigationVisibility(visible: Boolean) = Unit
}
