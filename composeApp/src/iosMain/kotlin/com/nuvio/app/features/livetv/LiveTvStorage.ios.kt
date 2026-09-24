package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.profiles.MAX_PROFILES
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults

actual object LiveTvStorage {
    private const val playlistUrlKey = "playlist_url"
    private const val playlistsBlobKey = "playlists_blob"
    private const val favoriteChannelIdsBlobKey = "favorite_channel_ids_blob"
    private const val lastWatchedChannelIdKey = "last_watched_channel_id"
    private const val navigationEnabledKey = "navigation_enabled"
    private const val stalkerSettingsKey = "stalker_settings"
    private const val xtreamSettingsKey = "xtream_settings"
    private const val guideUrlKey = "guide_url"
    private const val nativeNavigationVisibleKey = "NuvioLiveTvNavigationVisible"
    private const val nativeNavigationDidChangeNotification = "NuvioLiveTvNavigationVisibilityDidChange"
    private val sessionText = mutableMapOf<String, String>()
    private val sessionStalker = mutableMapOf<String, LiveTvStalkerSettings>()
    private val sessionXtream = mutableMapOf<String, LiveTvXtreamSettings>()
    private var didPurgeLegacySecrets = false

    private fun purgeLegacySecrets() {
        if (didPurgeLegacySecrets) return
        val defaults = NSUserDefaults.standardUserDefaults
        val keys = listOf(playlistUrlKey, playlistsBlobKey, stalkerSettingsKey, xtreamSettingsKey, guideUrlKey)
        keys.forEach { key ->
            defaults.removeObjectForKey(key)
            for (profileId in 0..MAX_PROFILES) {
                defaults.removeObjectForKey(ProfileScopedKey.of(key, profileId))
            }
        }
        didPurgeLegacySecrets = true
    }

    actual fun loadPlaylistUrl(): String? {
        purgeLegacySecrets()
        val key = ProfileScopedKey.of(playlistUrlKey)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
        return sessionText[key]
    }

    actual fun savePlaylistUrl(url: String) {
        sessionText[ProfileScopedKey.of(playlistUrlKey)] = url
    }

    actual fun loadPlaylistsBlob(): String? {
        purgeLegacySecrets()
        val key = ProfileScopedKey.of(playlistsBlobKey)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
        return sessionText[key]
    }

    actual fun savePlaylistsBlob(blob: String) {
        sessionText[ProfileScopedKey.of(playlistsBlobKey)] = blob
    }

    actual fun loadFavoriteChannelIdsBlob(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(favoriteChannelIdsBlobKey))

    actual fun saveFavoriteChannelIdsBlob(blob: String) {
        NSUserDefaults.standardUserDefaults.setObject(blob, forKey = ProfileScopedKey.of(favoriteChannelIdsBlobKey))
    }

    actual fun loadLastWatchedChannelId(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(lastWatchedChannelIdKey))

    actual fun saveLastWatchedChannelId(channelId: String) {
        NSUserDefaults.standardUserDefaults.setObject(channelId, forKey = ProfileScopedKey.of(lastWatchedChannelIdKey))
    }

    actual fun loadNavigationEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(navigationEnabledKey)
        return if (defaults.objectForKey(key) == null) null else defaults.boolForKey(key)
    }

    actual fun saveNavigationEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(
            enabled,
            forKey = ProfileScopedKey.of(navigationEnabledKey),
        )
    }

    actual fun loadStalkerSettings(): LiveTvStalkerSettings {
        purgeLegacySecrets()
        val key = ProfileScopedKey.of(stalkerSettingsKey)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
        return sessionStalker[key] ?: LiveTvStalkerSettings()
    }

    actual fun saveStalkerSettings(settings: LiveTvStalkerSettings) {
        sessionStalker[ProfileScopedKey.of(stalkerSettingsKey)] = settings
    }

    actual fun loadXtreamSettings(): LiveTvXtreamSettings {
        purgeLegacySecrets()
        val key = ProfileScopedKey.of(xtreamSettingsKey)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
        return sessionXtream[key] ?: LiveTvXtreamSettings()
    }

    actual fun saveXtreamSettings(settings: LiveTvXtreamSettings) {
        sessionXtream[ProfileScopedKey.of(xtreamSettingsKey)] = settings
    }

    actual fun loadGuideUrl(): String? {
        purgeLegacySecrets()
        val key = ProfileScopedKey.of(guideUrlKey)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
        return sessionText[key]
    }

    actual fun saveGuideUrl(url: String) {
        sessionText[ProfileScopedKey.of(guideUrlKey)] = url
    }

    actual fun publishNavigationVisibility(visible: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(visible, forKey = nativeNavigationVisibleKey)
        NSNotificationCenter.defaultCenter.postNotificationName(
            nativeNavigationDidChangeNotification,
            null,
        )
    }
}
