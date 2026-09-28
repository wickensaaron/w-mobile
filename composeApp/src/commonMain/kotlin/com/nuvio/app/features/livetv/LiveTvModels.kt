package com.nuvio.app.features.livetv

data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val group: String? = null,
    val playlistId: String? = null,
    val playlistName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val stalkerCommand: String? = null,
    val guideId: String? = null,
    val accountScope: LiveTvAccountScope? = null,
    val accountSourceGeneration: Long? = null,
    val sourceLoadGeneration: Int? = null,
)

data class LiveTvProgramme(
    val channelId: String,
    val title: String,
    val description: String? = null,
    val startEpochMs: Long,
    val stopEpochMs: Long,
)

data class LiveTvStalkerSettings(
    val portalUrl: String = "",
    val macAddress: String = "",
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = true,
) {
    val isConfigured: Boolean get() = portalUrl.isNotBlank() && macAddress.isNotBlank()
}

data class LiveTvXtreamSettings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = true,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

enum class LiveTvPlaylistType {
    Url,
    LocalFile,
}

data class LiveTvPlaylist(
    val id: String,
    val name: String,
    val type: LiveTvPlaylistType,
    val source: String,
    val isEnabled: Boolean = true,
)

data class LiveTvUiState(
    val playlistUrl: String = "",
    val playlists: List<LiveTvPlaylist> = emptyList(),
    val stalkerSettings: LiveTvStalkerSettings = LiveTvStalkerSettings(),
    val xtreamSettings: LiveTvXtreamSettings = LiveTvXtreamSettings(),
    val channels: List<LiveTvChannel> = emptyList(),
    val guideUrl: String = "",
    val programmes: Map<String, List<LiveTvProgramme>> = emptyMap(),
    val isGuideLoading: Boolean = false,
    val guideErrorMessage: String? = null,
    val favoriteChannelIds: Set<String> = emptySet(),
    val lastWatchedChannelId: String? = null,
    val isNavigationEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val accountSources: List<LiveTvAccountSourceSummary> = emptyList(),
    val isRestoringAccountSources: Boolean = false,
    val accountSourceErrorMessage: String? = null,
    val accountGuideSourceCount: Int = 0,
) {
    val hasPlaylist: Boolean
        get() = playlists.isNotEmpty() || playlistUrl.isNotBlank() ||
            stalkerSettings.isConfigured || xtreamSettings.isConfigured || accountSources.isNotEmpty()

    val hasGuideSources: Boolean get() = guideUrl.isNotBlank() || accountGuideSourceCount > 0

    val showInNavigation: Boolean
        get() = hasPlaylist && isNavigationEnabled
}

internal fun LiveTvUiState.programmesFor(channel: LiveTvChannel): List<LiveTvProgramme> {
    if (channel.accountScope != null) return programmes[channel.id].orEmpty()
    val key = channel.guideId ?: channel.name
    return programmes[key] ?: programmes.entries.firstOrNull { it.key.equals(key, true) }?.value.orEmpty()
}
