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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.time.Clock

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
        ++liveTvWorkEpoch
        if (channelRefreshJob?.isActive == true) catalogueNeedsRefresh = true
        if (guideRefreshJob?.isActive == true) guideNeedsRefresh = true
        channelRefreshJob?.cancel()
        guideRefreshJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = false, isGuideLoading = false)
    }

    private fun warmLiveTv() {
        if (!liveTvForeground || _uiState.value.isRestoringAccountSources) return
        if (catalogueNeedsRefresh) {
            if (channelRefreshJob?.isActive != true) refresh()
        } else if (guideNeedsRefresh && _uiState.value.hasGuideSources && guideRefreshJob?.isActive != true) {
            refreshGuide()
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
        importedSources = emptyList()
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
                importedSources = snapshot.providers
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
        )
        refreshGuide()
    }

    fun refreshGuide() {
        ensureLoaded()
        guideRefreshJob?.cancel()
        val currentState = _uiState.value
        val url = currentState.guideUrl
        val sources = importedSources.filter { it.enabled && it.guideUrl().isNotBlank() }
        val owner = loadedAccountScope
        val generation = accountSourceGeneration
        val configuration = loadedConfiguration
        val profileId = loadedProfileId
        val catalogueVersion = channelRefreshVersion
        val workEpoch = liveTvWorkEpoch
        val version = ++guideRefreshVersion
        fun isCurrentGuide(): Boolean = version == guideRefreshVersion && generation == accountSourceGeneration &&
            workEpoch == liveTvWorkEpoch &&
            (sources.isEmpty() || catalogueVersion == channelRefreshVersion) && loadedProfileId == profileId &&
            ProfileRepository.activeProfileId == profileId && loadedConfiguration == configuration &&
            ServerConfigurationRepository.active.value == configuration && loadedAccountScope == owner &&
            currentLiveTvAccountScope() == owner && _uiState.value.guideUrl == url
        if (url.isBlank() && sources.isEmpty()) {
            _uiState.value = _uiState.value.copy(programmes = emptyMap(), isGuideLoading = false, guideErrorMessage = null)
            guideNeedsRefresh = false
            return
        }
        if (url.isNotBlank() && !url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            guideNeedsRefresh = false
            _uiState.value = _uiState.value.copy(isGuideLoading = false, guideErrorMessage = "Enter an HTTP or HTTPS XMLTV URL.")
            return
        }
        _uiState.value = _uiState.value.copy(isGuideLoading = true, guideErrorMessage = null)
        guideRefreshJob = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val projection = LiveTvGuideProjection()
                    var failures = 0
                    val channelsBySource = mutableMapOf<String, MutableList<LiveTvChannel>>()
                    currentState.channels.forEachIndexed { index, channel ->
                        if (index % 256 == 0) {
                            currentCoroutineContext().ensureActive()
                            yield()
                        }
                        if (owner != null && channel.accountScope == owner && channel.accountSourceGeneration == generation &&
                            channel.playlistId != null) {
                            channelsBySource.getOrPut(channel.playlistId) { mutableListOf() } += channel
                        }
                    }
                    if (url.isNotBlank()) {
                        runCatching {
                            parseXmlTvGuide(httpGetTextWithHeaders(url, mapOf("Accept" to "application/xml, text/xml, */*")))
                        }.fold(onSuccess = { projection.appendManual(it) }, onFailure = {
                            if (it is CancellationException) throw it
                            failures++
                        })
                    }
                    for (source in sources) {
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentGuide() || !ownsSource(source, owner, generation)) return@withContext null
                        val sourceChannels = channelsBySource[source.id].orEmpty().let { channels ->
                            if (currentState.accountGuideSnapshot?.preferences?.ukOnly != false)
                                channels.filter(::isExplicitUkAccountGuideChannel) else channels
                        }
                        if (sourceChannels.isEmpty()) continue
                        if (!projection.hasCapacity) {
                            failures++
                            continue
                        }
                        runCatching {
                            val parsed = LiveTvAccountImport.providerGuide(source.guideUrl(), source.id, sourceChannels,
                                projection.remainingCapacity,
                                isCurrent = { isCurrentGuide() && ownsSource(source, owner, generation) })
                            currentCoroutineContext().ensureActive()
                            check(isCurrentGuide() && ownsSource(source, owner, generation)) { "Live TV guide source changed" }
                            // Streaming rows already carry exact owned source/channel IDs. Appending
                            // these immutable rows preserves the shared budget without a second copy.
                            projection.appendManual(parsed.programmes)
                            if (parsed.wasTruncated) failures++
                        }.onFailure {
                            if (it is CancellationException) throw it
                            failures++
                        }
                    }
                    projection.snapshot() to failures
                } ?: return@launch
                if (isCurrentGuide()) {
                    val (projection, failures) = result
                    guideNeedsRefresh = false
                    _uiState.value = _uiState.value.copy(programmes = projection.programmes, isGuideLoading = false,
                        guideErrorMessage = if (failures > 0 || projection.wasTruncated) "Some programme guides could not be loaded or exceed the device limit." else null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // URLs, tokens, and server responses may appear in exception text.
                log.w { "Failed to load XMLTV guide (${error::class.simpleName})" }
                if (isCurrentGuide()) {
                    guideNeedsRefresh = false
                    _uiState.value = _uiState.value.copy(isGuideLoading = false, guideErrorMessage = "Guide could not be loaded.")
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
            val loadedChannels = mutableListOf<LiveTvChannel>()
            val failedPlaylistNames = mutableListOf<String>()
            fun appendLocalChannels(channels: List<LiveTvChannel>) {
                val remaining = (MobileLiveTvCatalogueLimit - loadedChannels.size).coerceAtLeast(0)
                if (channels.size > remaining) failedPlaylistNames += "Device catalogue limit"
                loadedChannels += channels.take(remaining)
            }

            enabledPlaylists.forEach { playlist ->
                val result = runCatching {
                    val payload = when (playlist.type) {
                        LiveTvPlaylistType.Url -> withContext(Dispatchers.Default) {
                            httpGetTextWithHeaders(playlist.source, mapOf("Accept" to "application/x-mpegURL, text/plain, */*"))
                        }
                        LiveTvPlaylistType.LocalFile -> playlist.source
                    }
                    parseM3uPlaylist(payload, playlist)
                }

                result.fold(
                    onSuccess = ::appendLocalChannels,
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        failedPlaylistNames += playlist.name
                        // Exception messages can contain password-bearing playlist URLs.
                        log.w { "Failed to load a live TV playlist (${error::class.simpleName})" }
                    },
                )
            }

            if (currentState.xtreamSettings.isConfigured && currentState.xtreamSettings.isEnabled) {
                runCatching { fetchXtreamChannels(currentState.xtreamSettings) }.fold(
                    onSuccess = ::appendLocalChannels,
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        failedPlaylistNames += "Xtream"
                        log.w { "Failed to load Xtream provider (${error::class.simpleName})" }
                    },
                )
            }
            if (currentState.stalkerSettings.isConfigured && currentState.stalkerSettings.isEnabled) {
                runCatching { fetchStalkerChannels(currentState.stalkerSettings) }.fold(
                    onSuccess = ::appendLocalChannels,
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        failedPlaylistNames += "Stalker Portal"
                        log.w { "Failed to load Stalker provider (${error::class.simpleName})" }
                    },
                )
            }

            for (source in sources) {
                if (!ownsSource(source, owner, generation)) return@launch
                if (loadedChannels.size >= MobileLiveTvCatalogueLimit) {
                    failedPlaylistNames += "Device catalogue limit"
                    continue
                }
                val remaining = MobileLiveTvCatalogueLimit - loadedChannels.size
                runCatching {
                    withContext(Dispatchers.Default) {
                        currentCoroutineContext().ensureActive()
                        check(ownsSource(source, owner, generation)) { "Live TV source changed" }
                        val channels = when (source.type) {
                            "XTREAM" -> fetchXtreamChannels(source.xtreamSettings(), source) { ownsSource(source, owner, generation) }
                            else -> {
                                val payload = LiveTvAccountImport.providerText(source.endpoint,
                                    mapOf("Accept" to "application/x-mpegURL, text/plain, */*"),
                                    isCurrent = { ownsSource(source, owner, generation) })
                                val context = currentCoroutineContext()
                                parseImportedLiveTvPlaylist(payload, source, checkActive = { context.ensureActive() })
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        check(ownsSource(source, owner, generation)) { "Live TV source changed" }
                        require(channels.size <= remaining) { "Live TV catalogue exceeds device limit" }
                        val ownedChannels = ArrayList<LiveTvChannel>(channels.size)
                        channels.forEachIndexed { index, channel ->
                            if (index % 256 == 0) {
                                currentCoroutineContext().ensureActive()
                                yield()
                            }
                            ownedChannels += channel.copy(accountScope = owner, accountSourceGeneration = generation)
                        }
                        ownedChannels.toList()
                    }
                }.fold(onSuccess = {
                    if (ownsSource(source, owner, generation)) loadedChannels += it
                }, onFailure = {
                    if (it is CancellationException) throw it
                    failedPlaylistNames += "Account source"
                    log.w { "Could not load an account Live TV source (${it::class.simpleName})" }
                })
            }

            val channels = withContext(Dispatchers.Default) {
                val seen = mutableSetOf<String>()
                val result = ArrayList<LiveTvChannel>(loadedChannels.size)
                loadedChannels.forEachIndexed { index, channel ->
                    if (index % 256 == 0) {
                        currentCoroutineContext().ensureActive()
                        yield()
                    }
                    if (result.size < MobileLiveTvCatalogueLimit &&
                        seen.add(if (channel.accountScope != null) channel.id else "local:${channel.streamUrl}")) {
                        result += channel.copy(sourceLoadGeneration = version)
                    }
                }
                result.toList()
            }
            if (version != channelRefreshVersion || generation != accountSourceGeneration ||
                workEpoch != liveTvWorkEpoch || loadedAccountScope != owner || currentLiveTvAccountScope() != owner) return@launch
            catalogueNeedsRefresh = false
            _uiState.value = _uiState.value.copy(
                playlistUrl = playlists.firstEnabledUrlSource(),
                playlists = playlists,
                channels = channels,
                isLoading = false,
                errorMessage = when {
                    channels.isEmpty() && failedPlaylistNames.isNotEmpty() -> "Playlist could not be loaded."
                    channels.isEmpty() -> "No channels found in these playlists."
                    failedPlaylistNames.isNotEmpty() -> "Some live TV sources could not be loaded."
                    else -> null
                },
            )
            if (_uiState.value.hasGuideSources) refreshGuide()
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
