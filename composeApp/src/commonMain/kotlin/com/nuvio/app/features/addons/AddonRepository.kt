package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.sync.decodeRecoverableAccountPayload
import com.nuvio.app.core.sync.AccountReplacement
import com.nuvio.app.core.sync.replayAccountReplacement
import com.nuvio.app.features.watching.sync.currentNuvioSyncIdentity
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Serializable
private data class AddonRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
private data class AddonPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
private data class StoredAddonSync(
    val owner: String? = null,
    val hasRemoteSnapshot: Boolean = false,
    val pending: AccountReplacement<AddonPushItem>? = null,
    val names: Map<String, String> = emptyMap(),
    val recoveryPayload: String? = null,
)

private const val ADDON_PUSH_DEBOUNCE_MS = 500L
private const val STARTER_BOOTSTRAP_PENDING = "pending"
private const val STARTER_BOOTSTRAP_DONE = "done"
private val STARTER_ADDON_URLS = listOf("https://catalog.nuvio.tv/manifest.json")

object AddonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(AddonsUiState())
    val uiState: StateFlow<AddonsUiState> = _uiState.asStateFlow()

    private var generation = 0L
    private val syncMutex = Mutex()
    private var initialized = false
    private var currentProfileId: Int = 1
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private val pushJobsByProfile = mutableMapOf<Int, Job>()

    fun initialize() {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        if (initialized) return
        initialized = true
        currentProfileId = effectiveProfileId
        log.d { "initialize() — loading local addons for profile $currentProfileId" }

        val storedUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(currentProfileId))
            .let { saved ->
                if (AddonStorage.loadStarterBootstrapStatus(currentProfileId) != null) {
                    saved
                } else if (saved.isEmpty()) {
                    AddonStorage.saveInstalledAddonUrls(currentProfileId, STARTER_ADDON_URLS)
                    AddonStorage.saveStarterBootstrapStatus(currentProfileId, STARTER_BOOTSTRAP_PENDING)
                    STARTER_ADDON_URLS
                } else {
                    AddonStorage.saveStarterBootstrapStatus(currentProfileId, STARTER_BOOTSTRAP_DONE)
                    saved
                }
            }
        val enabledByUrl = loadLocalEnabledStates()
        val names = loadSyncState(currentProfileId).names
        log.d { "initialize() — local addon count: ${storedUrls.size}" }
        if (storedUrls.isEmpty()) return

        val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
        _uiState.value = AddonsUiState(
            addons = storedUrls.map { manifestUrl ->
                existingByUrl[manifestUrl].toPendingAddon(
                    manifestUrl = manifestUrl,
                    enabled = enabledByUrl[manifestUrl],
                    userSetName = names[manifestUrl],
                )
            },
        )

        storedUrls.forEach { manifestUrl ->
            val existing = existingByUrl[manifestUrl]
            val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == manifestUrl }
            if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                refreshAddon(manifestUrl)
            }
        }
    }

    fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (effectiveProfileId == currentProfileId && initialized) return
        generation++
        cancelActiveRefreshes()
        currentProfileId = effectiveProfileId
        initialized = false
        _uiState.value = AddonsUiState()
    }

    fun clearLocalState() {
        generation++
        cancelActiveRefreshes()
        pushJobsByProfile.values.forEach(Job::cancel)
        pushJobsByProfile.clear()
        currentProfileId = 1
        initialized = false
        _uiState.value = AddonsUiState()
    }

    suspend fun pullFromServer(profileId: Int) {
        val account = AuthRepository.state.value as? AuthState.Authenticated ?: return
        if (account.isAnonymous) return
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (effectiveProfileId != currentProfileId) return
        val owner = currentNuvioSyncIdentity() ?: return
        val epoch = generation
        syncMutex.withLock {
            val isCurrent = { generation == epoch && currentProfileId == effectiveProfileId && currentNuvioSyncIdentity() == owner }
            if (!isCurrent()) return@withLock
            // A fresh install may have only the provisional starter catalog. Its first
            // local edit must not replace an account's existing cloud addon list.
            if (loadSyncState(effectiveProfileId).hasRemoteSnapshot && !replayAddons(effectiveProfileId, isCurrent)) {
                return@withLock
            }
            pullRemoteAddons(effectiveProfileId, owner, isCurrent)
        }
    }

    private suspend fun pullRemoteAddons(profileId: Int, owner: String, isCurrent: () -> Boolean) {
        log.i { "pullFromServer() — profileId=$profileId, initialized=$initialized" }
        runCatching {
            val rows = SupabaseProvider.client.postgrest
                .from("addons")
                .select {
                    filter { eq("profile_id", profileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<AddonRow>()

            if (!isCurrent()) return@runCatching
            val stored = loadSyncState(profileId)
            if (stored.hasRemoteSnapshot && stored.pending != null) return@runCatching
            val rowsByUrl = linkedMapOf<String, AddonRow>()
            rows.forEach { row ->
                val manifestUrl = ensureManifestSuffix(row.url)
                if (!rowsByUrl.containsKey(manifestUrl)) {
                    rowsByUrl[manifestUrl] = row.copy(url = manifestUrl)
                }
            }

            val urls = rowsByUrl.keys.toList()
            log.i { "pullFromServer() — server returned ${rows.size} addons" }

            if (!stored.hasRemoteSnapshot) {
                if (urls.isEmpty()) {
                    // Keep the provisional local list for recovery, but never let a
                    // first-run starter list replace remote rows after a stale/empty read.
                    initialize()
                    return@runCatching
                }
                // Remote rows establish the first authoritative snapshot. An edit made
                // before that read was based on an incomplete list, so discard its
                // replacement intent rather than deleting unseen cloud addons.
                if (stored.pending != null) {
                    saveSyncState(profileId, stored.copy(pending = null))
                }
            }

            val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
            _uiState.value = AddonsUiState(
                addons = urls.map { url ->
                    val row = rowsByUrl[url]
                    existingByUrl[url].toPendingAddon(
                        manifestUrl = url,
                        userSetName = row?.name?.takeIf { it.isNotBlank() },
                        enabled = row?.enabled,
                    )
                },
            )
            saveSyncState(profileId, StoredAddonSync(
                owner = owner,
                hasRemoteSnapshot = true,
                names = rowsByUrl.mapNotNull { (url, row) -> row.name?.let { url to it } }.toMap(),
                recoveryPayload = loadSyncState(profileId).recoveryPayload,
            ))
            persist()
            AddonStorage.saveStarterBootstrapStatus(currentProfileId, STARTER_BOOTSTRAP_DONE)
            urls.forEach { url ->
                val existing = existingByUrl[url]
                val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == url }
                if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                    refreshAddon(url)
                }
            }
            initialized = true
            log.i { "pullFromServer() — applied ${urls.size} addons to state" }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.e { "pullFromServer() — FAILED (${error::class.simpleName})" }
        }
    }

    suspend fun awaitManifestsLoaded() {
        if (_uiState.value.addons.isEmpty()) return
        uiState.first { state ->
            state.addons.isEmpty() ||
                state.addons.any { it.manifest != null } ||
                state.addons.none { it.isRefreshing }
        }
    }

    suspend fun addAddon(rawUrl: String): AddAddonResult {
        val owner = currentNuvioSyncIdentity()
        val epoch = generation
        val profileId = currentProfileId
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            return AddAddonResult.Error(getString(Res.string.profile_primary_addons_required))
        }
        log.i { "addAddon() — requested" }
        val manifestUrl = try {
            normalizeManifestUrl(rawUrl)
        } catch (error: IllegalArgumentException) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_invalid_url))
        }

        if (_uiState.value.addons.any { it.manifestUrl == manifestUrl }) {
            return AddAddonResult.Error(getString(Res.string.addon_already_installed))
        }

        val manifest = try {
            withContext(Dispatchers.Default) {
                val payload = fetchAddonResponseText(manifestUrl)
                AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_load_manifest_failed))
        }
        if (generation != epoch || currentProfileId != profileId || currentNuvioSyncIdentity() != owner) {
            return AddAddonResult.Error(getString(Res.string.addon_load_manifest_failed))
        }

        _uiState.update { current ->
            current.copy(
                addons = current.addons + ManagedAddon(
                    manifestUrl = manifestUrl,
                    manifest = manifest,
                    isRefreshing = false,
                    errorMessage = null,
                ),
            )
        }
        persist()
        pushToServer()
        return AddAddonResult.Success(manifest)
    }

    fun removeAddon(manifestUrl: String) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        log.i { "removeAddon() — requested" }
        var changed = false
        _uiState.update { current ->
            val updatedAddons = current.addons.filterNot { it.manifestUrl == manifestUrl }
            changed = updatedAddons.size != current.addons.size
            if (changed) current.copy(addons = updatedAddons) else current
        }
        if (!changed) return
        persist()
        pushToServer()
    }

    fun moveAddon(fromIndex: Int, toIndex: Int) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var changed = false
        _uiState.update { current ->
            val addons = current.addons
            if (
                fromIndex !in addons.indices ||
                toIndex !in addons.indices ||
                fromIndex == toIndex
            ) {
                return@update current
            }

            val reordered = addons.toMutableList()
            val movingAddon = reordered.removeAt(fromIndex)
            reordered.add(toIndex, movingAddon)
            changed = true
            current.copy(addons = reordered)
        }
        if (!changed) return
        persist()
        pushToServer()
    }

    fun setAddonEnabled(manifestUrl: String, enabled: Boolean) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var shouldRefresh = false
        var changed = false
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl != manifestUrl || addon.enabled == enabled) {
                        addon
                    } else {
                        changed = true
                        shouldRefresh = enabled && addon.manifest == null && !addon.isRefreshing
                        addon.copy(enabled = enabled)
                    }
                },
            )
        }
        if (!changed) return
        persist()
        pushToServer()
        if (shouldRefresh) {
            refreshAddon(manifestUrl)
        }
    }

    fun refreshAll() {
        _uiState.value.addons.filter { it.enabled }.distinctBy { it.manifestUrl }.forEach { addon ->
            refreshAddon(
                manifestUrl = addon.manifestUrl,
                forceRefresh = true,
            )
        }
    }

    fun refreshAddon(
        manifestUrl: String,
        forceRefresh: Boolean = false,
    ) {
        val existingJob = activeRefreshJobs[manifestUrl]
        if (existingJob?.isActive == true) return

        markRefreshing(manifestUrl)
        var refreshJob: Job? = null
        refreshJob = scope.launch {
            try {
                val result = runCatching {
                    val payload = fetchAddonResponseText(
                        url = manifestUrl,
                        forceRefresh = forceRefresh,
                    )
                    AddonManifestParser.parse(
                        manifestUrl = manifestUrl,
                        payload = payload,
                    )
                }

                _uiState.update { current ->
                    current.copy(
                        addons = current.addons.map { addon ->
                            if (addon.manifestUrl != manifestUrl) {
                                addon
                            } else {
                                result.fold(
                                    onSuccess = { manifest ->
                                        addon.copy(
                                            manifest = manifest,
                                            isRefreshing = false,
                                            errorMessage = null,
                                        )
                                    },
                                    onFailure = { error ->
                                        addon.copy(
                                            isRefreshing = false,
                                            errorMessage = error.message ?: getString(Res.string.addon_load_manifest_failed),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            } finally {
                if (activeRefreshJobs[manifestUrl] === refreshJob) {
                    activeRefreshJobs.remove(manifestUrl)
                }
            }
        }
        activeRefreshJobs[manifestUrl] = refreshJob
    }

    private fun currentPushItems(): List<AddonPushItem> = _uiState.value.addons
        .distinctBy { it.manifestUrl }
        .mapIndexed { index, addon ->
            AddonPushItem(
                url = addon.manifestUrl,
                name = addon.userSetName?.takeIf { it.isNotBlank() } ?: addon.manifest?.name ?: "",
                enabled = addon.enabled,
                sortOrder = index,
            )
        }

    private fun loadSyncState(profileId: Int): StoredAddonSync {
        val raw = AddonStorage.loadSyncPayload(profileId) ?: return StoredAddonSync()
        val decoded = decodeRecoverableAccountPayload<StoredAddonSync>(json, raw, "pending")
        val stored = decoded.value ?: StoredAddonSync()
        return if (decoded.damagedPayload == null) stored else stored.copy(
            // Keep local URLs usable without treating damaged intent as an empty remote list.
            pending = AccountReplacement(null, emptyList()),
            recoveryPayload = decoded.damagedPayload,
        )
    }

    private fun saveSyncState(profileId: Int, stored: StoredAddonSync) =
        AddonStorage.saveSyncPayload(profileId, json.encodeToString(stored))

    private fun queueCurrentAddons(profileId: Int) {
        val owner = currentNuvioSyncIdentity()
        val items = currentPushItems()
        val stored = loadSyncState(profileId)
        saveSyncState(profileId, StoredAddonSync(
            owner = owner,
            hasRemoteSnapshot = stored.owner == owner && stored.hasRemoteSnapshot,
            pending = AccountReplacement(owner, items),
            names = items.associate { it.url to it.name },
            recoveryPayload = stored.recoveryPayload,
        ))
    }

    private suspend fun replayAddons(profileId: Int, isCurrent: () -> Boolean): Boolean {
        val stored = loadSyncState(profileId)
        if (!stored.hasRemoteSnapshot) return false
        val pending = stored.pending
        return replayAccountReplacement(
            pending = pending,
            currentOwner = ::currentNuvioSyncIdentity,
            isCurrent = { isCurrent() && loadSyncState(profileId).pending == pending },
            push = { addons ->
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_addons", json.encodeToJsonElement(addons))
                    putSyncOriginClientId()
                }
                SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
            },
            acknowledge = {
                saveSyncState(profileId, loadSyncState(profileId).copy(pending = null, hasRemoteSnapshot = true))
                AddonStorage.saveStarterBootstrapStatus(profileId, STARTER_BOOTSTRAP_DONE)
            },
            onFailure = { error -> log.w { "Keeping local addons for retry (${error::class.simpleName})" } },
        )
    }

    private fun pushToServer() {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        val profileId = currentProfileId
        // Write the exact list before scheduling network work, including empty lists.
        queueCurrentAddons(profileId)
        val account = AuthRepository.state.value as? AuthState.Authenticated ?: return
        if (account.isAnonymous) return
        val owner = currentNuvioSyncIdentity()
        val epoch = generation
        pushJobsByProfile[profileId]?.cancel()
        var pushJob: Job? = null
        pushJob = scope.launch {
            try {
                delay(ADDON_PUSH_DEBOUNCE_MS)
                if (loadSyncState(profileId).hasRemoteSnapshot) {
                    syncMutex.withLock {
                        replayAddons(profileId) {
                            generation == epoch && currentNuvioSyncIdentity() == owner
                        }
                    }
                } else if (generation == epoch && currentNuvioSyncIdentity() == owner) {
                    pullFromServer(profileId)
                }
            } finally {
                if (pushJobsByProfile[profileId] === pushJob) pushJobsByProfile.remove(profileId)
            }
        }
        pushJobsByProfile[profileId] = pushJob
    }

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl == manifestUrl) {
                        addon.copy(
                            isRefreshing = true,
                            errorMessage = null,
                        )
                    } else {
                        addon
                    }
                },
            )
        }
    }

    private fun persist() {
        val addons = _uiState.value.addons
        AddonStorage.saveInstalledAddonUrls(
            currentProfileId,
            dedupeManifestUrls(addons.map { it.manifestUrl }),
        )
        AddonStorage.saveAddonEnabledStates(
            currentProfileId,
            addons.associate { it.manifestUrl to it.enabled },
        )
    }

    private fun loadLocalEnabledStates(): Map<String, Boolean> =
        AddonStorage.loadAddonEnabledStates(currentProfileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }

    private fun cancelActiveRefreshes() {
        activeRefreshJobs.values.forEach(Job::cancel)
        activeRefreshJobs.clear()
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && active.profileIndex != 1 && active.usesPrimaryAddons) 1 else profileId
    }

    private fun isUsingPrimaryAddonsFromSecondaryProfile(): Boolean {
        val active = ProfileRepository.state.value.activeProfile
        return active != null && active.profileIndex != 1 && active.usesPrimaryAddons
    }
}

private fun ManagedAddon?.toPendingAddon(
    manifestUrl: String,
    userSetName: String? = null,
    enabled: Boolean? = null,
): ManagedAddon =
    when {
        this == null -> ManagedAddon(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: true,
            userSetName = userSetName,
            enabled = enabled ?: true,
        )
        manifest != null -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = false,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        isRefreshing -> copy(
            manifestUrl = manifestUrl,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        else -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: this.enabled,
            errorMessage = null,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
    }

private fun dedupeManifestUrls(urls: List<String>): List<String> =
    urls.map(::ensureManifestSuffix).distinct()

private fun ensureManifestSuffix(url: String): String {
    val path = url.substringBefore("?").trimEnd('/')
    val query = url.substringAfter("?", "")
    val withSuffix = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
    return if (query.isEmpty()) withSuffix else "$withSuffix?$query"
}

private fun normalizeManifestUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotEmpty()) { runBlocking { getString(Res.string.addons_error_enter_url) } }

    val normalizedScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("stremio://") -> "https://${trimmed.removePrefix("stremio://")}"
        else -> "https://$trimmed"
    }

    val withoutFragment = normalizedScheme.substringBefore("#")
    val query = withoutFragment.substringAfter("?", "")
    val path = withoutFragment.substringBefore("?").trimEnd('/')
    val manifestPath = if (path.endsWith("/manifest.json")) {
        path
    } else {
        "$path/manifest.json"
    }

    return if (query.isEmpty()) manifestPath else "$manifestPath?$query"
}
