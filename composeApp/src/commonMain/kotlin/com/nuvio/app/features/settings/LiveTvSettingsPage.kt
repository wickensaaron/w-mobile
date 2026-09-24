package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.livetv.LiveTvRepository
import com.nuvio.app.features.livetv.LiveTvUiState
import com.nuvio.app.features.livetv.LiveTvStalkerSettings
import com.nuvio.app.features.livetv.LiveTvXtreamSettings
import com.nuvio.app.features.livetv.rememberLiveTvPlaylistFilePicker
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.core.ui.nuvio
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
internal fun LiveTvTabScreen() {
    val uiState by remember(ProfileRepository.activeProfileId) {
        LiveTvRepository.ensureLoaded()
        LiveTvRepository.uiState
    }.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var guideMode by rememberSaveable { mutableStateOf(false) }
    LiveTvTheme {
        if (guideMode) {
            LiveTvGuideGrid(
                uiState = uiState,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                favoritesOnly = favoritesOnly,
                onFavoritesOnlyChange = { favoritesOnly = it },
                onChannelsClick = { guideMode = false },
            )
        } else {
            val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = statusBarPadding + 20.dp, bottom = 96.dp),
            ) {
                item(key = "live_tv_tab_title") {
                    Text("Live TV", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
                liveTvSettingsContent(
                    isTablet = false,
                    uiState = uiState,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    favoritesOnly = favoritesOnly,
                    onFavoritesOnlyChange = { favoritesOnly = it },
                    guideMode = guideMode,
                    onGuideModeChange = { guideMode = it },
                )
            }
        }
    }
}

/** The host app uses custom Nuvio tokens, so Material defaults need matching dark colors here. */
@Composable
private fun LiveTvTheme(content: @Composable () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentStrong,
            onPrimaryContainer = colors.textInverse,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceCard,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.borderDefault,
            outlineVariant = colors.borderSubtle,
            error = colors.danger,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.textPrimary, content = content)
    }
}

/** Source setup and channel browser share one Settings destination on Android and iOS. */
internal fun LazyListScope.liveTvSettingsContent(
    isTablet: Boolean,
    uiState: LiveTvUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    favoritesOnly: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    guideMode: Boolean,
    onGuideModeChange: (Boolean) -> Unit,
) {
    val visibleChannels = uiState.channels.filter { channel ->
        (!favoritesOnly || channel.id in uiState.favoriteChannelIds) &&
            (searchQuery.isBlank() || channel.name.contains(searchQuery.trim(), ignoreCase = true) ||
                channel.group?.contains(searchQuery.trim(), ignoreCase = true) == true)
    }
    item(key = "live_tv_sources") {
        LiveTvTheme { LiveTvSourceSettings(uiState, isTablet) }
    }
    item(key = "live_tv_channels_header") {
        LiveTvTheme {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Channels (${visibleChannels.size})", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("Search channels") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextButton(onClick = { onFavoritesOnlyChange(!favoritesOnly) }) {
                Text(if (favoritesOnly) "Show all channels" else "Show favourites only")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (guideMode) {
                    OutlinedButton(onClick = { onGuideModeChange(false) }) { Text("Channels") }
                    Button(onClick = {}) { Text("Guide") }
                } else {
                    Button(onClick = {}) { Text("Channels") }
                    OutlinedButton(onClick = { onGuideModeChange(true) }) { Text("Guide") }
                }
            }
            if (uiState.isLoading) Text("Loading channels…", style = MaterialTheme.typography.bodyMedium)
            uiState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (uiState.channels.isEmpty() && !uiState.isLoading) {
                Text("Add an M3U playlist or provider above to browse channels.")
            }
        }
        }
    }
    if (guideMode) {
        val guideKeys = uiState.programmes.keys.map(String::lowercase).toSet()
        val guideChannels = visibleChannels.filter { channel ->
            (channel.guideId ?: channel.name).lowercase() in guideKeys
        }
        if (guideChannels.isEmpty()) {
            item(key = "live_tv_no_guide") {
                LiveTvTheme {
                Text(
                    "No guide entries match these channels. Check the XMLTV URL and channel IDs.",
                    modifier = Modifier.padding(16.dp),
                )
                }
            }
        }
        items(guideChannels, key = { "guide:${it.id}" }) { channel ->
            LiveTvTheme { LiveTvGuideRow(channel, uiState) }
        }
    } else {
        items(visibleChannels, key = { it.id }) { channel ->
            LiveTvTheme { LiveTvChannelRow(channel, uiState) }
        }
    }
}

@Composable
private fun LiveTvSourceSettings(uiState: LiveTvUiState, isTablet: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(!uiState.hasPlaylist) }
    // URLs and credentials may be tokens; never put them in Compose saved state.
    var playlistUrl by remember { mutableStateOf("") }
    var guideUrl by remember(uiState.guideUrl) { mutableStateOf(uiState.guideUrl) }
    var xtreamServer by remember(uiState.xtreamSettings.serverUrl) { mutableStateOf(uiState.xtreamSettings.serverUrl) }
    var xtreamUser by remember(uiState.xtreamSettings.username) { mutableStateOf(uiState.xtreamSettings.username) }
    var xtreamPassword by remember(uiState.xtreamSettings.password) { mutableStateOf(uiState.xtreamSettings.password) }
    var stalkerPortal by remember(uiState.stalkerSettings.portalUrl) { mutableStateOf(uiState.stalkerSettings.portalUrl) }
    var stalkerMac by remember(uiState.stalkerSettings.macAddress) { mutableStateOf(uiState.stalkerSettings.macAddress) }
    var stalkerUser by remember(uiState.stalkerSettings.username) { mutableStateOf(uiState.stalkerSettings.username) }
    var stalkerPassword by remember(uiState.stalkerSettings.password) { mutableStateOf(uiState.stalkerSettings.password) }
    var pickerError by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLiveTvPlaylistFilePicker(
        onPlaylistLoaded = { name, content -> LiveTvRepository.addLocalPlaylist(name, content) },
        onError = { pickerError = "Local playlist could not be opened." },
    )
    val horizontalPadding = if (isTablet) 24.dp else 16.dp
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Sources", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide setup" else "Manage sources") }
        }
        if (!expanded) return@Column
        Text("Use a playlist or your own provider account. No channels are bundled with W. Sources are kept for this app session.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = playlistUrl,
            onValueChange = { playlistUrl = it },
            label = { Text("M3U playlist URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                LiveTvRepository.addPlaylistUrl(playlistUrl)
                playlistUrl = ""
            }, enabled = playlistUrl.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }) { Text("Add playlist") }
            if (picker.canPickFiles) {
                OutlinedButton(onClick = picker::launch) { Text("Import M3U file") }
            }
        }
        pickerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        uiState.playlists.forEach { playlist ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(playlist.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { LiveTvRepository.removePlaylist(playlist.id) }) { Text("Remove") }
            }
        }
        HorizontalDivider()
        Text("Xtream provider", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(xtreamServer, { xtreamServer = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(xtreamUser, { xtreamUser = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(xtreamPassword, { xtreamPassword = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { LiveTvRepository.saveXtreamSettings(LiveTvXtreamSettings(xtreamServer, xtreamUser, xtreamPassword)) },
                enabled = xtreamServer.isNotBlank() && xtreamUser.isNotBlank() && xtreamPassword.isNotBlank(),
            ) { Text("Save Xtream") }
            if (uiState.xtreamSettings.isConfigured) {
                TextButton(onClick = { LiveTvRepository.removeXtream() }) { Text("Remove") }
            }
        }
        HorizontalDivider()
        Text("Stalker portal", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(stalkerPortal, { stalkerPortal = it }, label = { Text("Portal URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(stalkerMac, { stalkerMac = it }, label = { Text("MAC address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(stalkerUser, { stalkerUser = it }, label = { Text("Username (if required)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(stalkerPassword, { stalkerPassword = it }, label = { Text("Password (if required)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { LiveTvRepository.saveStalkerSettings(LiveTvStalkerSettings(stalkerPortal, stalkerMac, stalkerUser, stalkerPassword)) },
                enabled = stalkerPortal.isNotBlank() && stalkerMac.isNotBlank(),
            ) { Text("Save portal") }
            if (uiState.stalkerSettings.isConfigured) {
                TextButton(onClick = { LiveTvRepository.removeStalker() }) { Text("Remove") }
            }
        }
        HorizontalDivider()
        Text("Programme guide", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(guideUrl, { guideUrl = it }, label = { Text("XMLTV URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { LiveTvRepository.saveGuideUrl(guideUrl) }) { Text("Save guide") }
            OutlinedButton(onClick = LiveTvRepository::refreshGuide, enabled = uiState.guideUrl.isNotBlank()) { Text("Refresh") }
        }
        if (uiState.isGuideLoading) Text("Loading programme guide…")
        uiState.guideErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = LiveTvRepository::refresh) { Text("Refresh channels") }
        }
    }
}

@Composable
private fun LiveTvChannelRow(channel: LiveTvChannel, uiState: LiveTvUiState) {
    val now = Clock.System.now().toEpochMilliseconds()
    val guideKey = channel.guideId ?: channel.name
    val guide = remember(uiState.programmes, guideKey) {
        uiState.programmes[guideKey]
            ?: uiState.programmes.entries.firstOrNull { it.key.equals(guideKey, ignoreCase = true) }?.value
            ?: emptyList()
    }
    val current = guide.firstOrNull { it.startEpochMs <= now && it.stopEpochMs > now }
    val next = guide.firstOrNull { it.startEpochMs > now }
    Column(modifier = Modifier.fillMaxWidth().clickable { LiveTvRepository.requestPlayback(channel) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(channel.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { LiveTvRepository.toggleFavoriteChannel(channel.id) }) {
                Text(if (channel.id in uiState.favoriteChannelIds) "★" else "☆")
            }
        }
        channel.group?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        current?.let { Text("Now: ${it.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        next?.let { Text("Next: ${it.title}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    HorizontalDivider()
}

@Composable
private fun LiveTvGuideRow(channel: LiveTvChannel, uiState: LiveTvUiState) {
    val now = Clock.System.now().toEpochMilliseconds()
    val guideKey = channel.guideId ?: channel.name
    val programmes = uiState.programmes[guideKey]
        ?: uiState.programmes.entries.firstOrNull { it.key.equals(guideKey, ignoreCase = true) }?.value
        ?: emptyList()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            channel.name,
            modifier = Modifier.fillMaxWidth().clickable { LiveTvRepository.requestPlayback(channel) },
            style = MaterialTheme.typography.titleMedium,
        )
        programmes.filter { it.stopEpochMs > now }.take(6).forEach { programme ->
            val isOnNow = programme.startEpochMs <= now && programme.stopEpochMs > now
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${programme.startEpochMs.utcHourMinute()}–${programme.stopEpochMs.utcHourMinute()} UTC",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    programme.title,
                    style = if (isOnNow) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    fontWeight = if (isOnNow) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(2f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider()
}

private fun Long.utcHourMinute(): String =
    Instant.fromEpochMilliseconds(this).toString().substring(11, 16)
