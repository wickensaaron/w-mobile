package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
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
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle

@Composable
internal fun LiveTvTabScreen() {
    val uiState by remember(ProfileRepository.activeProfileId) {
        LiveTvRepository.ensureLoaded()
        LiveTvRepository.uiState
    }.collectAsStateWithLifecycle()
    val localProfileId = ProfileRepository.activeProfileId
    val accountOwner = uiState.accountGuideOwner
    var searchQuery by rememberSaveable(localProfileId, accountOwner) { mutableStateOf("") }
    var favoritesOnly by rememberSaveable(localProfileId, accountOwner) { mutableStateOf(false) }
    var showLiveTv by rememberSaveable(localProfileId, accountOwner) { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { LiveTvRepository.restoreAccountGuidePreferences() }
    LiveTvTheme {
        if (showLiveTv) {
            LiveTvGuideGrid(
                uiState = uiState,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                favoritesOnly = favoritesOnly,
                onFavoritesOnlyChange = { favoritesOnly = it },
                onChannelsClick = { showLiveTv = false },
            )
        } else {
            val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val bottomPadding = nuvioSafeBottomPadding(24.dp)
            LazyColumn(
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                contentPadding = PaddingValues(top = statusBarPadding + 20.dp, bottom = bottomPadding),
            ) {
                item(key = "live_tv_tab_title") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Live TV settings", style = MaterialTheme.typography.headlineMedium)
                        OutlinedButton(onClick = { showLiveTv = true }) { Text("Back to Live TV") }
                    }
                }
                liveTvSettingsContent(isTablet = false, uiState = uiState)
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

/** Provider setup and organiser choices live in Settings; playback uses the prepared guide. */
internal fun LazyListScope.liveTvSettingsContent(isTablet: Boolean, uiState: LiveTvUiState) {
    item(key = "live_tv_sources") { LiveTvTheme { LiveTvSourceSettings(uiState, isTablet) } }
    item(key = "live_tv_guide_choices") { LiveTvTheme { LiveTvAccountGuideChoices(uiState) } }
}

@Composable
private fun LiveTvAccountGuideChoices(uiState: LiveTvUiState) {
    val owner = uiState.accountGuideOwner
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var openError by remember(owner) { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Guide choices", style = MaterialTheme.typography.titleLarge)
        Text("Hide categories, arrange your channels and choose the UK cleanup in your organiser.")
        if (owner != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    openError = null
                    runCatching { uriHandler.openUri("${owner.backend}/functions/v1/tv-logins-exchange?organise=live-tv&profile=${owner.profile}") }
                        .onFailure { openError = "The organiser could not be opened on this device." }
                }) { Text("Organise channels") }
                OutlinedButton(onClick = LiveTvRepository::restoreAccountGuidePreferences,
                    enabled = !uiState.isAccountGuideSyncing && !uiState.isAccountGuideSaving) { Text("Refresh choices") }
            }
            if (uiState.isAccountGuideSyncing) Text("Updating your guide choices…")
            uiState.accountGuideSyncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else Text("Sign in to use the same guide choices on your other devices.")
        openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { LiveTvRepository.saveGuideUrl(guideUrl) }) { Text("Save guide") }
            OutlinedButton(onClick = LiveTvRepository::refreshGuide, enabled = uiState.hasGuideSources) { Text("Refresh") }
        }
        if (uiState.isGuideLoading) Text("Loading programme guide…")
        uiState.guideErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = LiveTvRepository::refresh) { Text("Refresh channels") }
        }
        Text("Account sources", style = MaterialTheme.typography.titleMedium)
        if (uiState.isRestoringAccountSources) Text("Restoring your sources…")
        uiState.accountSources.forEach { source ->
            Text("${source.name} · ${source.type}${if (source.enabled) "" else " · Disabled"}")
        }
        uiState.accountSourceErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = LiveTvRepository::restoreAccountSources, enabled = !uiState.isRestoringAccountSources) {
            Text("Restore account sources")
        }
    }
}
