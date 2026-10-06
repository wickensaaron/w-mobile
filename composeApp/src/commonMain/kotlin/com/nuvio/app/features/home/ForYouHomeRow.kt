package com.nuvio.app.features.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.userId
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable
internal fun ForYouHomeRow(profileId: Int, sectionPadding: Dp, onPosterClick: ((MetaPreview) -> Unit)?) {
    val auth by AuthRepository.state.collectAsStateWithLifecycle()
    key(auth.userId, profileId) {
        ForYouProfileRow(profileId, forYouFeedbackKey(auth.userId, profileId), sectionPadding, onPosterClick)
    }
}

@Composable
private fun ForYouProfileRow(profileId: Int, feedbackKey: String, sectionPadding: Dp, onPosterClick: ((MetaPreview) -> Unit)?) {
    LaunchedEffect(Unit) { LibraryRepository.ensureLoaded(); WatchedRepository.ensureLoaded(); WatchProgressRepository.ensureLoaded(); TmdbSettingsRepository.ensureLoaded() }
    val library by LibraryRepository.uiState.collectAsStateWithLifecycle()
    val watched by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val progress by WatchProgressRepository.uiState.collectAsStateWithLifecycle()
    val settings by TmdbSettingsRepository.uiState.collectAsStateWithLifecycle()
    val posterStyle = rememberPosterCardStyleUiState()
    val cardWidth = if (posterStyle.catalogLandscapeModeEnabled) landscapePosterWidth(posterStyle.widthDp) else posterStyle.widthDp.dp
    val store = rememberForYouFeedbackStorage()
    var dismissed by remember(feedbackKey) { mutableStateOf(store.load(feedbackKey).orEmpty().lines().filter(String::isNotBlank).toSet()) }
    var lastDismissed by remember { mutableStateOf<String?>(null) }
    var picks by remember { mutableStateOf(emptyList<ForYouPick>()) }
    var loading by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val completed = remember(progress.entries) { progress.entries.filter { it.isCompleted } }
    val seeds = remember(library.items, watched.items, completed) {
        val saved = library.items.sortedByDescending { it.savedAtEpochMs }
            .filter { forYouType(it.type) != null }.distinctBy { forYouKey(it.type, it.id) }.take(3)
            .map { ForYouSeed(it.tmdbId?.let { id -> "tmdb:$id" } ?: it.id, it.type, it.name, 1.25) }
        val history = (watched.items.sortedByDescending { it.markedAtEpochMs }.map { ForYouSeed(it.id, it.type, it.name, 1.0) } +
            completed.sortedByDescending { it.lastUpdatedEpochMs }.map { ForYouSeed(it.parentMetaId, it.parentMetaType, it.title, 1.0) })
            .filter { forYouType(it.type) != null }.distinctBy { forYouKey(it.type, it.id) }.take(3)
        (saved + history).distinctBy { forYouKey(it.type, it.id) }
    }
    val excluded = remember(library.items, watched.items, completed) {
        buildSet {
            library.items.forEach { item ->
                add(forYouKey(item.type, item.id))
                item.imdbId?.let { add(forYouKey(item.type, it)) }
                item.tmdbId?.let { add(forYouKey(item.type, "tmdb:$it")) }
            }
            watched.items.forEach { add(forYouKey(it.type, it.id)) }
            completed.forEach { add(forYouKey(it.parentMetaType, it.parentMetaId)) }
        }
    }
    val ownsInputs = LibraryRepository.isLoadedForProfile(profileId) && WatchedRepository.isLoadedForProfile(profileId) &&
        WatchProgressRepository.isLoadedForProfile(profileId)
    ScreenActivityEffect(seeds, excluded, ownsInputs, library, watched, progress, settings.language, settings.apiKey, retry) { active ->
        if (!active) return@ScreenActivityEffect
        val librarySnapshot = library
        val watchedSnapshot = watched
        val progressSnapshot = progress
        picks = emptyList()
        loading = false
        if (seeds.isEmpty() || !ownsInputs) return@ScreenActivityEffect
        loading = true
        try {
            // Debounce repository updates during profile/provider transitions.
            delay(300)
            // Verify both ownership and exact snapshots after debounce, before sending personal seeds.
            if (!LibraryRepository.isLoadedForProfile(profileId) || !WatchedRepository.isLoadedForProfile(profileId) ||
                !WatchProgressRepository.isLoadedForProfile(profileId) || librarySnapshot != LibraryRepository.uiState.value ||
                watchedSnapshot != WatchedRepository.uiState.value || progressSnapshot != WatchProgressRepository.uiState.value) return@ScreenActivityEffect
            val result = withTimeoutOrNull(45_000) {
                val batches = mutableListOf<ForYouBatch>()
                for (seed in seeds) {
                    ensureActive()
                    val mediaType = if (forYouType(seed.type) == "series") "tv" else "movie"
                    val id = TmdbService.ensureTmdbId(seed.id, mediaType)?.toIntOrNull() ?: continue
                    val canonicalSeed = seed.copy(id = "tmdb:$id")
                    batches += ForYouBatch(canonicalSeed, TmdbMetadataService.fetchMoreLikeThis(id, mediaType, settings.language))
                }
                val allExcluded = excluded + batches.map { forYouKey(it.seed.type, it.seed.id) }
                val ranked = rankForYou(batches, allExcluded, limit = 48)
                // Resolve only the bounded shortlist, rather than the user's entire history.
                val gate = Semaphore(3)
                coroutineScope {
                    ranked.map { pick -> async {
                        gate.withPermit {
                            val imdb = TmdbService.tmdbToImdb(pick.item.id.substringAfter(':').toInt(), if (pick.item.type == "series") "tv" else "movie")
                            ensureActive()
                            // Without an alias we cannot safely rule out an IMDb history match.
                            if (imdb == null || forYouKey(pick.item.type, imdb) in allExcluded) null else pick
                        }
                    } }.awaitAll().filterNotNull()
                }
            }.orEmpty()
            ensureActive()
            // Do not publish an old owner's response while profile repositories are switching.
            if (LibraryRepository.isLoadedForProfile(profileId) && WatchedRepository.isLoadedForProfile(profileId) &&
                WatchProgressRepository.isLoadedForProfile(profileId) && librarySnapshot == LibraryRepository.uiState.value &&
                watchedSnapshot == WatchedRepository.uiState.value && progressSnapshot == WatchProgressRepository.uiState.value) {
                picks = result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            picks = emptyList()
        } finally { loading = false }
    }
    val visible = if (ownsInputs) visibleForYou(picks, dismissed) else emptyList()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(Modifier.padding(horizontal = sectionPadding)) {
            Text("For you", modifier = Modifier.semantics { heading() }, color = MaterialTheme.nuvio.colors.textPrimary, style = MaterialTheme.typography.titleLarge)
            if (lastDismissed != null) TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                dismissed = dismissed - lastDismissed!!
                store.save(feedbackKey, dismissed.joinToString("\n"))
                lastDismissed = null
            }) { Text("Undo hide", color = MaterialTheme.nuvio.colors.textPrimary) }
            if (dismissed.isNotEmpty()) TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                dismissed = emptySet(); lastDismissed = null
                store.save(feedbackKey, "")
            }) { Text("Reset hidden picks", color = MaterialTheme.nuvio.colors.textPrimary) }
        }
        if (visible.isEmpty()) {
            Text(when {
                !ownsInputs -> "Loading your library and viewing history…"
                seeds.isEmpty() -> "Save a few favourites to your library to get personal picks here."
                loading -> "Finding titles connected to your library and viewing…"
                picks.isNotEmpty() -> "You've hidden these picks. Reset hidden picks to see them again."
                else -> "No new picks right now. Save more favourites or try again."
            }, Modifier.padding(horizontal = sectionPadding), color = MaterialTheme.nuvio.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            if (ownsInputs && !loading && seeds.isNotEmpty() && picks.isEmpty()) TextButton(onClick = { retry++ }, modifier = Modifier.padding(horizontal = sectionPadding).heightIn(min = 48.dp)) { Text("Try again", color = MaterialTheme.nuvio.colors.textPrimary) }
        } else {
            Text("Based on your saved and watched titles", Modifier.padding(horizontal = sectionPadding), color = MaterialTheme.nuvio.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
            NuvioShelfSection(
                title = "",
                entries = visible,
                rowContentPadding = PaddingValues(horizontal = sectionPadding),
                itemSpacing = 10.dp,
                key = { forYouKey(it.item.type, it.item.id) },
            ) { pick ->
                    Column(Modifier.width(cardWidth)) {
                        HomePosterCard(pick.item, onClick = onPosterClick?.let { { it(pick.item) } })
                        ForYouDismissButton(pick.item.name, onClick = {
                            val id = forYouKey(pick.item.type, pick.item.id)
                            dismissed = dismissed + id; lastDismissed = id
                            store.save(feedbackKey, dismissed.joinToString("\n"))
                        })
                    }
            }
        }
    }
}

@Composable
internal fun ForYouDismissButton(title: String, onClick: () -> Unit) {
    TextButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
            contentDescription = "Not for me: $title"
        },
        onClick = onClick,
    ) {
        Text("Not for me", color = MaterialTheme.nuvio.colors.textPrimary)
    }
}
