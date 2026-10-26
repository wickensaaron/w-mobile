package com.nuvio.app.features.livetv

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.auth
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.TimeSource

object LiveTvRepository {
    private val log = Logger.withTag("LiveTvRepository")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _uiState = MutableStateFlow(LiveTvUiState())
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()
    val playbackRequests = MutableSharedFlow<LiveTvChannel>(extraBufferCapacity = 1)

    private var loadedProfileId: Int? = null
    private var guideRefreshVersion = 0
    private var channelRefreshVersion = 0
    private var loadedAccountScope: LiveTvAccountScope? = null
    private var loadedConfiguration: ServerConfiguration? = null
    private var importedSources: List<ImportedLiveTvSource> = emptyList()
    private var accountSourceGeneration = 0L
    private var observerJob: Job? = null
    private var accountImportJob: Job? = null
    private var accountGuideJob: Job? = null
    private var accountGuideSaveJob: Job? = null
    private var accountGuideVersion = 0L
    private var channelRefreshJob: Job? = null
    private var guideRefreshJob: Job? = null
    private var playbackPreparationJob: Job? = null
    private var liveTvForeground = false
    private var liveTvWorkEpoch = 0L
    private var catalogueNeedsRefresh = true
    private var guideNeedsRefresh = true
    private val guideCache = LiveTvGuideMemoryCache()

    /** Keep account discovery active globally, but load large provider data only for the Live TV screen. */
    fun enterLiveTv() {
        if (liveTvForeground) { ensureLoaded(); return }
        ensureLoaded()
        liveTvForeground = true
        warmLiveTv()
    }

    /** A player launched from Live TV must retain its independent playback preparation job. */
    fun leaveLiveTv() {
        if (!liveTvForeground) return
        liveTvForeground = false
        // Finish bounded work already requested by this screen. Returning must not restart a
        // multi-megabyte guide transfer. Account/profile/source changes still cancel it below.
    }

    private fun warmLiveTv() {
        if (!liveTvForeground || _uiState.value.isRestoringAccountSources) return
        if (catalogueNeedsRefresh) {
            if (channelRefreshJob?.isActive != true) refresh()
        } else if (guideNeedsRefresh && _uiState.value.hasGuideSources && guideRefreshJob?.isActive != true) {
            loadGuide()
        }
    }

    fun startObserving() {
        if (observerJob != null) return
        observerJob = scope.launch {
            combine(AuthRepository.state, ProfileRepository.state, ServerConfigurationRepository.active) { _, _, _ -> SupabaseProvider.client }
                .collectLatest { client ->
                    ensureLoaded()
                    warmLiveTv()
                    client.auth.sessionStatus.collect { ensureLoaded(); warmLiveTv() }
                }
        }
    }

    fun ensureLoaded() {
        startObserving()
        val profileId = ProfileRepository.activeProfileId
        val owner = currentLiveTvAccountScope()
        val configuration = ServerConfigurationRepository.active.value
        if (loadedProfileId == profileId && loadedAccountScope == owner && loadedConfiguration == configuration) return
        accountImportJob?.cancel()
        accountGuideJob?.cancel()
        accountGuideSaveJob?.cancel()
        ++accountGuideVersion
        channelRefreshJob?.cancel()
        guideRefreshJob?.cancel()
        playbackPreparationJob?.cancel()
        LiveTvCatchupPlaybackRegistry.clear()
        importedSources = emptyList()
        guideCache.clear()
        ++accountSourceGeneration
        loadedAccountScope = owner
        loadedConfiguration = configuration
        loadedProfileId = profileId
        clearStalkerSession()
        ++channelRefreshVersion
        ++guideRefreshVersion
        ++liveTvWorkEpoch
        catalogueNeedsRefresh = true
        guideNeedsRefresh = true
        val playlists = loadSavedPlaylists()
        _uiState.value = LiveTvUiState(
            playlistUrl = playlists.firstEnabledUrlSource(),
            playlists = playlists,
            stalkerSettings = LiveTvStorage.loadStalkerSettings(),
            xtreamSettings = LiveTvStorage.loadXtreamSettings(),
            guideUrl = LiveTvStorage.loadGuideUrl().orEmpty(),
            favoriteChannelIds = loadFavoriteChannelIds(),
            lastWatchedChannelId = LiveTvStorage.loadLastWatchedChannelId(),
            isNavigationEnabled = LiveTvStorage.loadNavigationEnabled() ?: true,
            accountGuideOwner = owner,
            accountSourceGeneration = accountSourceGeneration,
        )
        publishNavigationVisibility()
        if (owner != null) restoreAccountSources()
    }

    /** Refresh sources after returning to the app, without restarting an initial account import. */
    fun refreshAccountSources() {
        val alreadyLoaded = loadedProfileId == ProfileRepository.activeProfileId &&
            loadedAccountScope == currentLiveTvAccountScope() &&
            loadedConfiguration == ServerConfigurationRepository.active.value
        ensureLoaded()
        if (alreadyLoaded && loadedAccountScope != null && accountImportJob?.isActive != true) {
            restoreAccountSources()
        }
    }

    /** One-way pull only. Manual mobile sources are kept in their separate local lane. */
    fun restoreAccountSources() {
        val owner = loadedAccountScope ?: return
        val profileId = loadedProfileId
        val configuration = loadedConfiguration
        val requestedGeneration = accountSourceGeneration
        accountImportJob?.cancel()
        accountGuideJob?.cancel()
        accountGuideSaveJob?.cancel()
        ++accountGuideVersion
        _uiState.value = _uiState.value.copy(isRestoringAccountSources = true, accountSourceErrorMessage = null,
            isAccountGuideSyncing = false, isAccountGuideSaving = false)
        accountImportJob = scope.launch {
            try {
                val snapshot = LiveTvAccountImport.pull(owner)
                if (loadedAccountScope != owner || currentLiveTvAccountScope() != owner ||
                    loadedProfileId != profileId || ProfileRepository.activeProfileId != profileId ||
                    loadedConfiguration != configuration || ServerConfigurationRepository.active.value != configuration ||
                    accountSourceGeneration != requestedGeneration) return@launch
                if (snapshot.providers == importedSources) {
                    _uiState.value = _uiState.value.copy(isRestoringAccountSources = false,
                        accountSourceErrorMessage = null)
                    warmLiveTv()
                    restoreAccountGuidePreferences()
                    return@launch
                }
                importedSources = snapshot.providers
                guideCache.clear()
                ++accountSourceGeneration
                playbackPreparationJob?.cancel()
                channelRefreshJob?.cancel()
                guideRefreshJob?.cancel()
                ++liveTvWorkEpoch
                catalogueNeedsRefresh = true
                guideNeedsRefresh = true
                val previousImportedIds = _uiState.value.channels.filter { it.accountScope != null }.map { it.id }.toSet()
                _uiState.value = _uiState.value.copy(
                    accountSources = importedSources.map(ImportedLiveTvSource::summary),
                    accountGuideSourceCount = importedSources.count { it.enabled && it.guideUrl().isNotBlank() },
                    channels = _uiState.value.channels.filter { it.accountScope == null },
                    programmes = _uiState.value.programmes.filterKeys { it !in previousImportedIds },
                    favoriteChannelIds = loadFavoriteChannelIds(),
                    accountGuideOwner = owner,
                    accountSourceGeneration = accountSourceGeneration,
                    accountGuideSnapshot = null,
                    accountGuideSyncMessage = null,
                    isRestoringAccountSources = false,
                    accountSourceErrorMessage = null,
                    isLoading = false,
                    isGuideLoading = false,
                )
                publishNavigationVisibility()
                warmLiveTv()
                restoreAccountGuidePreferences()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w { "Could not restore account Live TV sources (${error::class.simpleName})" }
                if (loadedAccountScope == owner && currentLiveTvAccountScope() == owner &&
                    loadedProfileId == profileId && ProfileRepository.activeProfileId == profileId &&
                    loadedConfiguration == configuration && ServerConfigurationRepository.active.value == configuration &&
                    accountSourceGeneration == requestedGeneration) {
                    _uiState.value = _uiState.value.copy(isRestoringAccountSources = false,
                        accountSourceErrorMessage = "Account sources could not be restored. Try again when connected.")
                    warmLiveTv()
                }
            }
        }
    }

    private fun isCurrentGuideAccount(owner: LiveTvAccountScope, generation: Long): Boolean =
        loadedAccountScope == owner && currentLiveTvAccountScope() == owner && generation == accountSourceGeneration &&
            loadedConfiguration == ServerConfigurationRepository.active.value && loadedProfileId == owner.profile &&
            ProfileRepository.activeProfileId == owner.profile

    private suspend fun pullAccountGuide(owner: LiveTvAccountScope, generation: Long,
        isCurrent: () -> Boolean): LiveTvAccountGuideSnapshot {
        val sources = importedSources.filter { it.enabled }.map { it.id }.toSet()
        check(isCurrentGuideAccount(owner, generation) && isCurrent())
        val payload = LiveTvAccountGuideSync.pullPayload(owner, isCurrent)
        return withContext(Dispatchers.Default) { decodeLiveTvAccountGuideSnapshot(payload, sources) }
    }

    private fun publishAccountGuide(snapshot: LiveTvAccountGuideSnapshot) {
        val previousUkOnly = _uiState.value.accountGuideSnapshot?.preferences?.ukOnly ?: true
        val enabledSources = importedSources.filter { it.enabled }.map { it.id }.toSet()
        val favourites = snapshot.preferences?.favouriteIds.orEmpty().filter { it.substringBefore(':') in enabledSources }
        _uiState.value = _uiState.value.copy(accountGuideSnapshot = snapshot,
            favoriteChannelIds = loadFavoriteChannelIds() + favourites, accountGuideSyncMessage = null)
        if (previousUkOnly != (snapshot.preferences?.ukOnly ?: true) && _uiState.value.channels.isNotEmpty()) refreshGuide()
    }

    fun restoreAccountGuidePreferences() {
        ensureLoaded()
        val owner = loadedAccountScope ?: return
        if (_uiState.value.isRestoringAccountSources || _uiState.value.isAccountGuideSaving) return
        val generation = accountSourceGeneration
        val version = ++accountGuideVersion
        accountGuideJob?.cancel()
        fun isCurrent() = version == accountGuideVersion && isCurrentGuideAccount(owner, generation)
        _uiState.value = _uiState.value.copy(isAccountGuideSyncing = true, accountGuideSyncMessage = null)
        accountGuideJob = scope.launch {
            try {
                val snapshot = pullAccountGuide(owner, generation, ::isCurrent)
                if (isCurrent()) {
                    publishAccountGuide(snapshot)
                    _uiState.value = _uiState.value.copy(isAccountGuideSyncing = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent()) _uiState.value = _uiState.value.copy(isAccountGuideSyncing = false,
                    accountGuideSyncMessage = "Your guide choices could not be updated. Try Refresh choices when connected.")
            }
        }
    }

    /** One confirmed account write at a time; a conflict reapplies only this explicit favourite intent. */
    fun toggleFavoriteGuideRow(row: LiveTvAccountGuideRow) {
        ensureLoaded()
        val channel = row.channel
        if (!isCurrentPlaybackRequest(channel)) return
        if (channel.accountScope == null) { toggleFavoriteChannel(channel.id); return }
        val owner = loadedAccountScope ?: return
        val generation = accountSourceGeneration
        if (_uiState.value.isAccountGuideSaving || _uiState.value.isAccountGuideSyncing || _uiState.value.isRestoringAccountSources) return
        val catalogueVersion = channelRefreshVersion
        val snapshot = _uiState.value.accountGuideSnapshot ?: run {
            _uiState.value = _uiState.value.copy(accountGuideSyncMessage = "Refresh choices before saving account favourites.")
            return
        }
        val ids = (row.alternatives + channel).filter {
            it.accountScope == owner && isCurrentPlaybackRequest(it)
        }.map { it.id }.toSet()
        val shouldAdd = !isLiveTvGuideRowFavourite(row, snapshot.preferences?.favouriteIds.orEmpty().toSet(),
            _uiState.value.programmes, Clock.System.now().toEpochMilliseconds())
        val version = ++accountGuideVersion
        accountGuideJob?.cancel()
        fun isCurrent() = version == accountGuideVersion && isCurrentGuideAccount(owner, generation) &&
            catalogueVersion == channelRefreshVersion
        _uiState.value = _uiState.value.copy(isAccountGuideSaving = true, accountGuideSyncMessage = null)
        accountGuideSaveJob = scope.launch {
            try {
                fun applyIntent(base: LiveTvAccountGuideSnapshot): LiveTvAccountGuidePreferences {
                    return applyLiveTvAccountFavouriteIntent(base, channel.id, ids, shouldAdd)
                }
                val saved = try {
                    LiveTvAccountGuideSync.savePreferences(owner, snapshot, applyIntent(snapshot), ::isCurrent)
                } catch (_: LiveTvGuideRevisionConflict) {
                    val latest = pullAccountGuide(owner, generation, ::isCurrent)
                    check(isCurrent() && isCurrentPlaybackRequest(channel))
                    LiveTvAccountGuideSync.savePreferences(owner, latest, applyIntent(latest), ::isCurrent)
                }
                if (isCurrent()) {
                    publishAccountGuide(saved)
                    _uiState.value = _uiState.value.copy(isAccountGuideSaving = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent()) _uiState.value = _uiState.value.copy(isAccountGuideSaving = false,
                    accountGuideSyncMessage = "This favourite could not be saved. Refresh choices, then try again.")
            }
        }
    }

    internal val catchupRequests = MutableSharedFlow<Pair<LiveTvCatchupRequest, Boolean>>(extraBufferCapacity = 1)

    internal fun requestCatchup(request: LiveTvCatchupRequest, resume: Boolean = false) {
        if (resolveCatchupPlayback(ProfileRepository.activeProfileId, request) != null) catchupRequests.tryEmit(request to resume)
    }

    private fun catchupOwner(): LiveTvCatchupOwner? {
        if (loadedProfileId != ProfileRepository.activeProfileId || loadedAccountScope != currentLiveTvAccountScope() ||
            loadedConfiguration != ServerConfigurationRepository.active.value) return null
        val profile = ProfileRepository.state.value.activeProfile ?: return null
        return LiveTvCatchupOwner(profile.userId, profile.profileIndex,
            ServerConfigurationRepository.active.value.backendUrl.trim().trimEnd('/'), loadedAccountScope == null)
    }

    internal fun catchupSelection(channel: LiveTvChannel): LiveTvCatchupSelection? {
        if (!isCurrentPlaybackRequest(channel)) return null
        val owner = catchupOwner() ?: return null
        val archive = channel.archive ?: return null
        val source = if (channel.accountScope != null) {
            val imported = importedSources.singleOrNull { it.id == channel.playlistId && it.enabled && it.type == "XTREAM" } ?: return null
            MobileLiveTvArchiveSource(imported.id, imported.id, imported.name, imported.endpoint,
                imported.username, imported.password, imported.epgUrl)
        } else {
            val settings = _uiState.value.xtreamSettings
            if (channel.playlistId != "provider:xtream" || !settings.isConfigured || !settings.isEnabled) return null
            MobileLiveTvArchiveSource("xtream", "local-xtream", "Xtream", settings.serverUrl, settings.username, settings.password)
        }
        if (!channel.id.startsWith("${source.id}:") || !Regex("[0-9]{1,20}").matches(channel.id.removePrefix("${source.id}:"))) return null
        return LiveTvCatchupSelection(owner, source, channel, archive)
    }

    internal fun ownsCatchup(selection: LiveTvCatchupSelection): Boolean {
        val current = catchupSelection(selection.channel) ?: return false
        return current.owner == selection.owner && current.source == selection.source && current.archive == selection.archive
    }

    internal fun resolveCatchupPlayback(profileId: Int, request: LiveTvCatchupRequest): LiveTvCatchupPlayback? {
        if (profileId != ProfileRepository.activeProfileId) return null
        val clock = LiveTvArchivePlatform.clock ?: return null
        val url = resolveLiveTvXtreamCatchupUrl(request, clock, Clock.System.now().toEpochMilliseconds()) ?: return null
        return LiveTvCatchupPlayback(request, url, liveTvCatchupHistoryId(request))
    }

    internal fun catchupHistorySelection(profileId: Int, videoId: String): LiveTvCatchupSelection? {
        if (profileId != ProfileRepository.activeProfileId) return null
        val identity = parseLiveTvCatchupHistoryId(videoId) ?: return null
        val channelId = "${if (identity.sourceId == "local-xtream") "xtream" else identity.sourceId}:${identity.streamId}"
        val channel = _uiState.value.channels.singleOrNull { it.id == channelId } ?: return null
        return catchupSelection(channel)?.takeIf { liveTvCatchupFingerprint(it) == identity.fingerprint }
    }

    internal suspend fun resolveCatchupHistoryPlayback(profileId: Int, videoId: String): LiveTvCatchupPlayback? {
        val identity = parseLiveTvCatchupHistoryId(videoId) ?: return null
        ensureLoaded()
        if (profileId != ProfileRepository.activeProfileId) return null
        if (catchupHistorySelection(profileId, videoId) == null && (catalogueNeedsRefresh || _uiState.value.isRestoringAccountSources)) {
            val wasForeground = liveTvForeground
            try {
                enterLiveTv()
                withTimeoutOrNull(20_000) {
                    uiState.first { !it.isRestoringAccountSources && !it.isLoading && !catalogueNeedsRefresh }
                } ?: return null
            } finally {
                if (!wasForeground) leaveLiveTv()
            }
        }
        val selection = catchupHistorySelection(profileId, videoId) ?: return null
        val clock = LiveTvArchivePlatform.clock ?: return null
        val loader = LiveTvCatchupLoader(clock)
        val zone = loader.timezone(selection) { ownsCatchup(selection) }.zone ?: return null
        val now = Clock.System.now().toEpochMilliseconds()
        val day = clock.localTimestamp(identity.startMs, zone).take(10)
        val history = loader.history(selection, zone, day, now) { ownsCatchup(selection) }
        val programme = history.programmes.singleOrNull { it.startMs == identity.startMs && it.endMs == identity.endMs } ?: return null
        val request = LiveTvCatchupRequest.fromHistory(history, programme, now) ?: return null
        return resolveCatchupPlayback(profileId, request)?.takeIf { it.historyId == videoId }
    }

    fun isCurrentPlaybackRequest(channel: LiveTvChannel): Boolean {
        if (loadedProfileId != ProfileRepository.activeProfileId || loadedConfiguration != ServerConfigurationRepository.active.value) return false
        if (loadedAccountScope != currentLiveTvAccountScope()) return false
        if (channel.sourceLoadGeneration != channelRefreshVersion) return false
        val resident = _uiState.value.channels.singleOrNull { it.id == channel.id && it.playlistId == channel.playlistId } ?: return false
        if (resident != channel && (resident.stalkerCommand.isNullOrBlank() ||
                resident.copy(streamUrl = channel.streamUrl, headers = channel.headers) != channel)) return false
        if (channel.accountScope == null) return true
        return ownsImportedLiveTvChannel(channel, loadedAccountScope, currentLiveTvAccountScope(), accountSourceGeneration, importedSources)
    }

    private fun ownsSource(source: ImportedLiveTvSource, owner: LiveTvAccountScope?, generation: Long): Boolean =
        owner != null && owner == loadedAccountScope && owner == currentLiveTvAccountScope() &&
            generation == accountSourceGeneration && source.enabled && source in importedSources &&
            loadedConfiguration == ServerConfigurationRepository.active.value

    fun savePlaylistUrl(url: String) {
        ensureLoaded()
        val normalized = url.trim()
        val playlists = if (normalized.isBlank()) {
            emptyList()
        } else {
            listOf(createUrlPlaylist(normalized))
        }
        persistPlaylists(playlists)
        _uiState.value = _uiState.value.copy(
            playlistUrl = playlists.firstEnabledUrlSource(),
            playlists = playlists,
            channels = emptyList(),
            isLoading = false,
            errorMessage = null,
        )
        publishNavigationVisibility()
        if (playlists.isNotEmpty()) {
            refresh()
        }
    }

    fun addPlaylistUrl(url: String) {
        addPlaylistUrl(name = null, url = url)
    }

    fun addPlaylistUrl(name: String?, url: String) {
        ensureLoaded()
        val normalized = url.trim()
        if (normalized.isBlank()) return

        val current = _uiState.value.playlists
        if (current.any { it.type == LiveTvPlaylistType.Url && it.source.equals(normalized, ignoreCase = true) }) {
            return
        }

        val playlists = current + createUrlPlaylist(normalized, name)
        persistPlaylists(playlists)
        _uiState.value = _uiState.value.copy(
            playlistUrl = playlists.firstEnabledUrlSource(),
            playlists = playlists,
            errorMessage = null,
        )
        publishNavigationVisibility()
        refresh()
    }

    fun addLocalPlaylist(fileName: String?, content: String) {
        addLocalPlaylist(name = null, fileName = fileName, content = content)
    }

    fun addLocalPlaylist(name: String?, fileName: String?, content: String) {
        ensureLoaded()
        val normalizedContent = content.trim()
        if (normalizedContent.isBlank()) return

        val fallbackName = name?.trim()?.takeIf(String::isNotBlank)
            ?: fileName
                ?.let { file -> file.substringBeforeLast('.', missingDelimiterValue = file) }
                ?.trim()
                ?.takeIf(String::isNotBlank)
            ?: "Local playlist"
        val playlist = LiveTvPlaylist(
            id = stablePlaylistId("local:${fallbackName}:${normalizedContent.hashCode()}:${Random.nextInt()}", _uiState.value.playlists.size),
            name = fallbackName,
            type = LiveTvPlaylistType.LocalFile,
            source = normalizedContent,
            isEnabled = true,
        )
        val playlists = _uiState.value.playlists + playlist
        persistPlaylists(playlists)
        _uiState.value = _uiState.value.copy(
            playlistUrl = playlists.firstEnabledUrlSource(),
            playlists = playlists,
            errorMessage = null,
        )
        publishNavigationVisibility()
        refresh()
    }

    fun updatePlaylist(playlistId: String, name: String, source: String) {
        ensureLoaded()
        val current = _uiState.value.playlists
        val existing = current.firstOrNull { it.id == playlistId } ?: return
        val normalizedName = name.trim().ifBlank { existing.name }
        val normalizedSource = source.trim()
        if (normalizedSource.isBlank()) return
        if (existing.type == LiveTvPlaylistType.Url && current.any {
                it.id != playlistId &&
                    it.type == LiveTvPlaylistType.Url &&
                    it.source.equals(normalizedSource, ignoreCase = true)
            }
        ) {
            return
        }

        val playlists = current.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(
                    name = normalizedName,
                    source = normalizedSource,
                )
            } else {
                playlist
            }
        }
        persistPlaylists(playlists)
        _uiState.value = _uiState.value.copy(
            playlistUrl = playlists.firstEnabledUrlSource(),
            playlists = playlists,
            errorMessage = null,
        )
        publishNavigationVisibility()
        refresh()
    }

    fun removePlaylist(playlistId: String) {
        ensureLoaded()
        val playlists = _uiState.value.playlists.filterNot { it.id == playlistId }
        persistPlaylists(playlists)
        _uiState.value = _uiState.value.copy(
            playlistUrl = playlists.firstEnabledUrlSource(),
            playlists = playlists,
            channels = emptyList(),
            isLoading = false,
            errorMessage = null,
        )
        publishNavigationVisibility()
        if (playlists.any { it.isEnabled }) {
            refresh()
        }
    }

    fun setPlaylistEnabled(playlistId: String, isEnabled: Boolean) {
        ensureLoaded()
        val current = _uiState.value.playlists
        if (current.none { it.id == playlistId }) return

        val playlists = current.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(isEnabled = isEnabled)
            } else {
                playlist
            }
        }
        persistPlaylists(playlists)
        _uiState.value = _uiState.value.copy(
            playlistUrl = playlists.firstEnabledUrlSource(),
            playlists = playlists,
            channels = emptyList(),
            isLoading = false,
            errorMessage = null,
        )
        publishNavigationVisibility()
        if (playlists.any { it.isEnabled }) {
            refresh()
        }
    }

    fun setNavigationEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.isNavigationEnabled == enabled) return

        LiveTvStorage.saveNavigationEnabled(enabled)
        _uiState.value = _uiState.value.copy(isNavigationEnabled = enabled)
        publishNavigationVisibility()
    }

    fun saveStalkerSettings(settings: LiveTvStalkerSettings) {
        ensureLoaded()
        clearStalkerSession()
        val normalized = settings.copy(
            portalUrl = settings.portalUrl.trim().trimEnd('/'),
            macAddress = settings.macAddress.trim().uppercase(),
            username = settings.username.trim(),
            password = settings.password.trim(),
        )
        LiveTvStorage.saveStalkerSettings(normalized)
        _uiState.value = _uiState.value.copy(stalkerSettings = normalized, errorMessage = null)
        publishNavigationVisibility()
        refresh()
    }

    fun saveXtreamSettings(settings: LiveTvXtreamSettings) {
        ensureLoaded()
        val normalized = settings.copy(
            serverUrl = settings.serverUrl.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/'),
            username = settings.username.trim(),
            password = settings.password.trim(),
        )
        LiveTvStorage.saveXtreamSettings(normalized)
        _uiState.value = _uiState.value.copy(xtreamSettings = normalized, errorMessage = null)
        publishNavigationVisibility()
        refresh()
    }

    fun removeStalker() = saveStalkerSettings(LiveTvStalkerSettings())
    fun removeXtream() = saveXtreamSettings(LiveTvXtreamSettings())

    fun saveGuideUrl(url: String) {
        ensureLoaded()
        val normalized = url.trim()
        LiveTvStorage.saveGuideUrl(normalized)
        _uiState.value = _uiState.value.copy(
            guideUrl = normalized,
            programmes = emptyMap(),
            guideErrorMessage = null,
            guideRequestFailed = false,
        )
        refreshGuide()
    }

    fun refreshGuide() {
        guideCache.clear()
        loadGuide()
    }

    private fun loadGuide() {
        ensureLoaded()
        guideRefreshJob?.cancel()
        val state = _uiState.value
        val url = state.guideUrl
        val sources = importedSources.filter { it.enabled && it.guideUrl().isNotBlank() }
        val owner = loadedAccountScope
        val generation = accountSourceGeneration
        val configuration = loadedConfiguration
        val profileId = loadedProfileId
        val catalogueVersion = channelRefreshVersion
        val workEpoch = liveTvWorkEpoch
        val version = ++guideRefreshVersion
        fun isCurrentGuide(): Boolean = version == guideRefreshVersion && generation == accountSourceGeneration &&
            workEpoch == liveTvWorkEpoch && catalogueVersion == channelRefreshVersion &&
            loadedProfileId == profileId && ProfileRepository.activeProfileId == profileId &&
            loadedConfiguration == configuration && ServerConfigurationRepository.active.value == configuration &&
            loadedAccountScope == owner && currentLiveTvAccountScope() == owner && _uiState.value.guideUrl == url
        if (url.isBlank() && sources.isEmpty()) {
            _uiState.value = _uiState.value.copy(programmes = emptyMap(), isGuideLoading = false,
                guideErrorMessage = null, guideRequestFailed = false)
            guideNeedsRefresh = false
            return
        }
        if (url.isNotBlank() && !url.startsWith("https://", true) && !url.startsWith("http://", true)) {
            guideNeedsRefresh = false
            _uiState.value = _uiState.value.copy(isGuideLoading = false,
                guideErrorMessage = "Enter an HTTP or HTTPS XMLTV URL.", guideRequestFailed = true)
            return
        }
        _uiState.value = _uiState.value.copy(isGuideLoading = true, guideErrorMessage = null, guideRequestFailed = false)
        guideRefreshJob = scope.launch {
            val started = TimeSource.Monotonic.markNow()
            try {
                val tasks = mutableListOf<suspend () -> ImportedXmlTvResult>()
                val taskSources = mutableListOf<ImportedLiveTvSource?>()
                val taskChannels = mutableListOf<List<LiveTvChannel>>()
                val cached = mutableListOf<Boolean>()
                // Fair per-source allocations keep the total retained guide within the existing
                // 100k-row device limit even while two parsers are active.
                val budget = MobileLiveTvProgrammeLimit / (sources.size + if (url.isNotBlank()) 1 else 0).coerceAtLeast(1)
                if (url.isNotBlank()) {
                    tasks += suspend { ImportedXmlTvResult(parseXmlTvGuide(httpGetTextWithHeaders(url,
                        mapOf("Accept" to "application/xml, text/xml, */*"))), false) }
                    taskSources += null; taskChannels += emptyList<LiveTvChannel>(); cached += false
                }
                val channelsBySource = withContext(Dispatchers.Default) {
                    state.channels.filter { it.accountScope == owner && it.accountSourceGeneration == generation }
                        .groupBy { it.playlistId }
                }
                for (source in sources) {
                    val channels = channelsBySource[source.id].orEmpty().let {
                        if (state.accountGuideSnapshot?.preferences?.ukOnly != false) it.filter(::isExplicitUkAccountGuideChannel) else it
                    }
                    if (channels.isEmpty()) continue
                    val saved = owner?.let { guideCache.get(it, source, channels, budget, Clock.System.now().toEpochMilliseconds()) }
                    tasks += suspend {
                        saved ?: LiveTvAccountImport.providerGuide(source.guideUrl(), source.id, channels, budget,
                            isCurrent = { isCurrentGuide() && ownsSource(source, owner, generation) })
                    }
                    taskSources += source; taskChannels += channels; cached += (saved != null)
                }
                val results = MutableList<ImportedXmlTvResult?>(tasks.size) { null }
                var failures = 0
                var failureMessage: String? = null
                var completed = 0
                var firstRowsLogged = false
                loadLiveTvSources(tasks, parallelism = 2) { index, outcome ->
                    if (!isCurrentGuide()) throw CancellationException("Live TV guide changed")
                    completed++
                    outcome.fold(onSuccess = { parsed ->
                        results[index] = parsed
                        val source = taskSources[index]
                        if (!cached[index] && source != null && owner != null) {
                            guideCache.put(owner, source, taskChannels[index], budget, Clock.System.now().toEpochMilliseconds(), parsed)
                        }
                        if (parsed.wasTruncated) failures++
                    }, onFailure = { error ->
                        failures++
                        if (error is LiveTvGuideLoadException) failureMessage = error.userMessage
                        log.w { "Live TV guide source failed (${error::class.simpleName})" }
                    })
                    val projection = withContext(Dispatchers.Default) {
                        val merged = LiveTvGuideProjection()
                        results.filterNotNull().forEach { merged.appendManual(it.programmes) }
                        merged.snapshot()
                    }
                    if (!isCurrentGuide()) throw CancellationException("Live TV guide changed")
                    val finished = completed == tasks.size
                    _uiState.value = _uiState.value.copy(programmes = projection.programmes, isGuideLoading = !finished,
                        guideRequestFailed = finished && projection.programmeCount == 0 && failures > 0,
                        guideErrorMessage = when {
                            !finished -> null
                            projection.programmeCount == 0 && failures > 0 -> failureMessage ?: "Programme guide could not load. Check Sources, then retry."
                            projection.programmeCount == 0 -> "No upcoming programmes matched these channels. Check their guide IDs in Sources."
                            failures > 0 || projection.wasTruncated -> "Some programme sources failed or exceeded the device guide limit."
                            else -> null
                        })
                    if (!firstRowsLogged && projection.programmeCount > 0) {
                        firstRowsLogged = true
                        log.i { "Live TV first guide rows: channels=${projection.programmes.size}, elapsedMs=${started.elapsedNow().inWholeMilliseconds}" }
                    }
                }
                if (isCurrentGuide()) {
                    guideNeedsRefresh = false
                    _uiState.value = _uiState.value.copy(isGuideLoading = false)
                    log.i { "Live TV guide ready: sources=${tasks.size}, failed=$failures, elapsedMs=${started.elapsedNow().inWholeMilliseconds}" }
                    // A catalogue provider may have finished while this guide was downloading.
                    // Reuse completed feeds and load only the newly available provider channels.
                    if (_uiState.value.channels !== state.channels) loadGuide()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                log.w { "Failed to load XMLTV guide (${error::class.simpleName})" }
                if (isCurrentGuide()) {
                    guideNeedsRefresh = false
                    _uiState.value = _uiState.value.copy(isGuideLoading = false,
                        guideErrorMessage = "Guide could not be loaded.", guideRequestFailed = true)
                }
            }
        }
    }

    suspend fun prepareForPlayback(channel: LiveTvChannel): LiveTvChannel =
        if (channel.stalkerCommand.isNullOrBlank()) channel
        else preparePortalChannelForPlayback(channel, _uiState.value.stalkerSettings)

    fun requestPlayback(channel: LiveTvChannel) {
        ensureLoaded()
        if (!isCurrentPlaybackRequest(channel)) return
        playbackPreparationJob?.cancel()
        playbackPreparationJob = scope.launch {
            try {
                val prepared = prepareForPlayback(channel)
                if (!isCurrentPlaybackRequest(channel)) return@launch
                markChannelWatched(channel)
                playbackRequests.emit(prepared)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w { "Failed to prepare live TV channel (${error::class.simpleName})" }
                _uiState.value = _uiState.value.copy(errorMessage = "Channel could not be played.")
            }
        }
    }

    fun refresh() {
        ensureLoaded()
        channelRefreshJob?.cancel()
        guideRefreshJob?.cancel()
        ++guideRefreshVersion
        guideNeedsRefresh = true
        accountGuideSaveJob?.cancel()
        if (_uiState.value.isAccountGuideSaving) _uiState.value = _uiState.value.copy(isAccountGuideSaving = false,
            accountGuideSyncMessage = "Sources changed before this favourite was confirmed. Refresh choices to check it.")
        val version = ++channelRefreshVersion
        _uiState.value = _uiState.value.copy(isGuideLoading = false)
        val currentState = _uiState.value
        val sources = importedSources.filter { it.enabled }
        val owner = loadedAccountScope
        val generation = accountSourceGeneration
        val workEpoch = liveTvWorkEpoch
        val playlists = currentState.playlists
        val profileId = loadedProfileId
        val configuration = loadedConfiguration
        if (!currentState.hasPlaylist) {
            catalogueNeedsRefresh = false
            _uiState.value = _uiState.value.copy(
                playlistUrl = "",
                playlists = emptyList(),
                channels = emptyList(),
                isLoading = false,
                errorMessage = null,
            )
            publishNavigationVisibility()
            return
        }

        val enabledPlaylists = playlists.filter { it.isEnabled }
        val hasEnabledPortal = (currentState.xtreamSettings.isConfigured && currentState.xtreamSettings.isEnabled) ||
            (currentState.stalkerSettings.isConfigured && currentState.stalkerSettings.isEnabled)
        if (enabledPlaylists.isEmpty() && !hasEnabledPortal && sources.isEmpty()) {
            catalogueNeedsRefresh = false
            _uiState.value = _uiState.value.copy(
                playlistUrl = "",
                playlists = playlists,
                channels = emptyList(),
                isLoading = false,
                errorMessage = null,
            )
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        channelRefreshJob = scope.launch {
            val started = TimeSource.Monotonic.markNow()
            fun isCurrentCatalogue() = version == channelRefreshVersion && generation == accountSourceGeneration &&
                workEpoch == liveTvWorkEpoch && loadedAccountScope == owner && currentLiveTvAccountScope() == owner &&
                loadedProfileId == profileId && ProfileRepository.activeProfileId == profileId &&
                loadedConfiguration == configuration && ServerConfigurationRepository.active.value == configuration
            val tasks = mutableListOf<suspend () -> List<LiveTvChannel>>()
            enabledPlaylists.forEach { playlist ->
                tasks += suspend {
                    val payload = when (playlist.type) {
                        LiveTvPlaylistType.Url -> httpGetTextWithHeaders(playlist.source,
                            mapOf("Accept" to "application/x-mpegURL, text/plain, */*"))
                        LiveTvPlaylistType.LocalFile -> playlist.source
                    }
                    parseM3uPlaylist(payload, playlist)
                }
            }
            if (currentState.xtreamSettings.isConfigured && currentState.xtreamSettings.isEnabled)
                tasks += suspend { fetchXtreamChannels(currentState.xtreamSettings) }
            if (currentState.stalkerSettings.isConfigured && currentState.stalkerSettings.isEnabled)
                tasks += suspend { fetchStalkerChannels(currentState.stalkerSettings) }
            for (source in sources) {
                tasks += suspend {
                    check(isCurrentCatalogue() && ownsSource(source, owner, generation)) { "Live TV source changed" }
                    val channels = when (source.type) {
                        "XTREAM" -> fetchXtreamChannels(source.xtreamSettings(), source) { isCurrentCatalogue() && ownsSource(source, owner, generation) }
                        else -> {
                            val payload = LiveTvAccountImport.providerText(source.endpoint,
                                mapOf("Accept" to "application/x-mpegURL, text/plain, */*"),
                                isCurrent = { isCurrentCatalogue() && ownsSource(source, owner, generation) })
                            val context = currentCoroutineContext()
                            parseImportedLiveTvPlaylist(payload, source, checkActive = { context.ensureActive() })
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    check(isCurrentCatalogue() && ownsSource(source, owner, generation)) { "Live TV source changed" }
                    channels.map { it.copy(accountScope = owner, accountSourceGeneration = generation) }
                }
            }
            val loaded = MutableList<List<LiveTvChannel>?>(tasks.size) { null }
            var failed = 0
            var completed = 0
            var firstChannelsLogged = false
            loadLiveTvSources(tasks) { index, outcome ->
                if (!isCurrentCatalogue()) throw CancellationException("Live TV catalogue changed")
                completed++
                outcome.fold(onSuccess = { loaded[index] = it }, onFailure = {
                    failed++
                    log.w { "Live TV channel source failed (${it::class.simpleName})" }
                })
                val channels = withContext(Dispatchers.Default) { mergeLiveTvChannelLoads(loaded, version) }
                if (!isCurrentCatalogue()) throw CancellationException("Live TV catalogue changed")
                val finished = completed == tasks.size
                catalogueNeedsRefresh = !finished
                _uiState.value = _uiState.value.copy(playlistUrl = playlists.firstEnabledUrlSource(),
                    playlists = playlists, channels = channels, isLoading = !finished,
                    errorMessage = when {
                        !finished -> null
                        channels.isEmpty() && failed > 0 -> "Playlist could not be loaded."
                        channels.isEmpty() -> "No channels found in these playlists."
                        failed > 0 || loaded.filterNotNull().sumOf { it.size } > MobileLiveTvCatalogueLimit -> "Some live TV sources could not be loaded."
                        else -> null
                    })
                if (channels.isNotEmpty() && !firstChannelsLogged) {
                    firstChannelsLogged = true
                    log.i { "Live TV first channels: count=${channels.size}, elapsedMs=${started.elapsedNow().inWholeMilliseconds}" }
                }
                // Start the first usable provider's guide immediately. A provider that times out
                // must not hold every other channel behind its catalogue/guide request.
                if (_uiState.value.hasGuideSources && guideRefreshJob?.isActive != true) loadGuide()
            }
            if (isCurrentCatalogue()) {
                catalogueNeedsRefresh = false
                log.i { "Live TV channels ready: sources=${tasks.size}, failed=$failed, elapsedMs=${started.elapsedNow().inWholeMilliseconds}" }
            }
        }
    }

    fun toggleFavoriteChannel(channelId: String) {
        ensureLoaded()
        val channel = _uiState.value.channels.singleOrNull { it.id == channelId } ?: return
        if (channel.accountScope != null) {
            toggleFavoriteGuideRow(LiveTvAccountGuideRow(channel, channel.name, channel.group.orEmpty(), null, null))
            return
        }
        val favorites = _uiState.value.favoriteChannelIds
            .let { current ->
                if (channelId in current) {
                    current - channelId
                } else {
                    current + channelId
                }
            }
        persistFavoriteChannelIds(favorites)
        _uiState.value = _uiState.value.copy(favoriteChannelIds = favorites)
    }

    fun markChannelWatched(channel: LiveTvChannel) {
        ensureLoaded()
        LiveTvStorage.saveLastWatchedChannelId(channel.id)
        _uiState.value = _uiState.value.copy(lastWatchedChannelId = channel.id)
    }

    private fun publishNavigationVisibility() {
        LiveTvStorage.publishNavigationVisibility(_uiState.value.showInNavigation)
    }

    private fun loadSavedPlaylists(): List<LiveTvPlaylist> {
        val saved = decodePlaylists(LiveTvStorage.loadPlaylistsBlob().orEmpty())
        if (saved.isNotEmpty()) return saved

        val legacyUrl = LiveTvStorage.loadPlaylistUrl()?.trim().orEmpty()
        return if (legacyUrl.isBlank()) emptyList() else listOf(createUrlPlaylist(legacyUrl))
    }

    private fun persistPlaylists(playlists: List<LiveTvPlaylist>) {
        LiveTvStorage.savePlaylistsBlob(encodePlaylists(playlists))
        LiveTvStorage.savePlaylistUrl(playlists.firstUrlSource())
    }

    private fun loadFavoriteChannelIds(): Set<String> =
        LiveTvStorage.loadFavoriteChannelIdsBlob()
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { accountGuideProviderPattern.matches(it.substringBefore(':')) && ':' in it }
            .toSet()

    private fun persistFavoriteChannelIds(channelIds: Set<String>) {
        LiveTvStorage.saveFavoriteChannelIdsBlob(channelIds.sorted().joinToString("\n"))
    }
}

internal fun parseM3uPlaylist(
    payload: String,
    playlist: LiveTvPlaylist? = null,
): List<LiveTvChannel> {
    val channels = mutableListOf<LiveTvChannel>()
    var pendingInfo: M3uInfo? = null

    payload.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach { line ->
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = parseExtInf(line)
                }
                line.startsWith("#") -> Unit
                else -> {
                    if (channels.size >= MobileLiveTvCatalogueLimit) return@forEach
                    val streamUrl = line
                    val info = pendingInfo
                    val name = info?.name?.takeIf(String::isNotBlank)
                        ?: "Channel"
                    channels += LiveTvChannel(
                        id = stableChannelId(streamUrl, channels.size),
                        name = name,
                        streamUrl = streamUrl,
                        logoUrl = info?.logoUrl?.takeIf(String::isNotBlank),
                        group = info?.group?.takeIf(String::isNotBlank),
                        playlistId = playlist?.id,
                        playlistName = playlist?.name,
                        guideId = info?.guideId,
                    )
                    pendingInfo = null
                }
            }
        }

    return channels.distinctBy { it.streamUrl }
}

private data class M3uInfo(
    val name: String,
    val logoUrl: String?,
    val group: String?,
    val guideId: String?,
)

private fun parseExtInf(line: String): M3uInfo {
    val name = line.substringAfter(',', missingDelimiterValue = "")
        .trim()
        .ifBlank {
            readM3uAttribute(line, "tvg-name").orEmpty()
        }
    return M3uInfo(
        name = name,
        logoUrl = readM3uAttribute(line, "tvg-logo"),
        group = readM3uAttribute(line, "group-title"),
        guideId = readM3uAttribute(line, "tvg-id") ?: readM3uAttribute(line, "tvg-name"),
    )
}

private fun readM3uAttribute(line: String, key: String): String? {
    val marker = "$key=\""
    val start = line.indexOf(marker, ignoreCase = true)
    if (start < 0) return null
    val valueStart = start + marker.length
    val valueEnd = line.indexOf('"', startIndex = valueStart).takeIf { it >= 0 } ?: return null
    return line.substring(valueStart, valueEnd).trim()
}

private fun createUrlPlaylist(url: String, customName: String? = null): LiveTvPlaylist =
    LiveTvPlaylist(
        id = stablePlaylistId(url, 0),
        // A playlist URL path may itself contain a token or password.
        name = customName?.trim()?.takeIf(String::isNotBlank) ?: "M3U playlist",
        type = LiveTvPlaylistType.Url,
        source = url,
    )

private fun List<LiveTvPlaylist>.firstUrlSource(): String =
    firstOrNull { it.type == LiveTvPlaylistType.Url }?.source.orEmpty()

private fun List<LiveTvPlaylist>.firstEnabledUrlSource(): String =
    firstOrNull { it.isEnabled && it.type == LiveTvPlaylistType.Url }?.source.orEmpty()

private fun decodePlaylistEnabled(value: String?): Boolean =
    value?.equals("false", ignoreCase = true) != true

private const val playlistRecordSeparator = "\u001E"
private const val playlistFieldSeparator = "\u001F"

private fun encodePlaylists(playlists: List<LiveTvPlaylist>): String =
    playlists.joinToString(playlistRecordSeparator) { playlist ->
        listOf(
            playlist.id,
            playlist.name,
            playlist.type.name,
            playlist.source,
            playlist.isEnabled.toString(),
        ).joinToString(playlistFieldSeparator) { escapePlaylistField(it) }
    }

private fun decodePlaylists(blob: String): List<LiveTvPlaylist> =
    blob
        .split(playlistRecordSeparator)
        .mapNotNull { record ->
            if (record.isBlank()) return@mapNotNull null
            val fields = record.split(playlistFieldSeparator).map(::unescapePlaylistField)
            val type = fields.getOrNull(2)?.let { raw ->
                runCatching { LiveTvPlaylistType.valueOf(raw) }.getOrNull()
            } ?: return@mapNotNull null
            LiveTvPlaylist(
                id = fields.getOrNull(0)?.takeIf(String::isNotBlank) ?: return@mapNotNull null,
                name = fields.getOrNull(1)?.takeIf(String::isNotBlank) ?: "M3U playlist",
                type = type,
                source = fields.getOrNull(3)?.takeIf(String::isNotBlank) ?: return@mapNotNull null,
                isEnabled = decodePlaylistEnabled(fields.getOrNull(4)),
            )
        }

private fun escapePlaylistField(value: String): String =
    value
        .replace("%", "%25")
        .replace(playlistRecordSeparator, "%1E")
        .replace(playlistFieldSeparator, "%1F")

private fun unescapePlaylistField(value: String): String =
    value
        .replace("%1F", playlistFieldSeparator)
        .replace("%1E", playlistRecordSeparator)
        .replace("%25", "%")

private fun stableChannelId(streamUrl: String, index: Int): String =
    "live:${streamUrl.hashCode().toUInt().toString(16)}:$index"

private fun stablePlaylistId(source: String, index: Int): String =
    "playlist:${source.hashCode().toUInt().toString(16)}:$index"
