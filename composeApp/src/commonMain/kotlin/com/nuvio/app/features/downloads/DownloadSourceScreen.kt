package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.resolveDownloadStreams
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Review a user-selected batch; resolving sources never starts playback or a transfer. */
@Composable
fun DownloadSourceScreen(launch: StreamLaunch, onBack: () -> Unit, onOpenDownloads: () -> Unit, onOpenSavedDownloads: () -> Unit = onOpenDownloads) {
    val profile by ProfileRepository.state.collectAsStateWithLifecycle()
    val downloads by remember { DownloadsRepository.ensureLoaded(); DownloadsRepository.uiState }.collectAsStateWithLifecycle()
    val currentBackend by ServerConfigurationRepository.active.collectAsStateWithLifecycle()
    val backend = remember(launch) { currentBackend }
    fun ownsSelection(): Boolean {
        val active = ProfileRepository.state.value.activeProfile
        return ProfileRepository.activeProfileId == launch.profileId && active?.id == launch.downloadOwnerProfileId &&
            active?.userId == launch.downloadOwnerUserId && !launch.downloadOwnerUserId.isNullOrBlank() &&
            ServerConfigurationRepository.active.value == backend
    }
    val ownerMatches = ownsSelection() && profile.activeProfile != null
    val meta = remember(launch, profile.activeProfile?.id) {
        if (ownerMatches) MetaDetailsRepository.peek(launch.parentMetaType ?: launch.type, launch.parentMetaId ?: launch.videoId) else null
    }
    val fallback = remember(launch) { MetaVideo(launch.videoId, launch.episodeTitle ?: launch.title,
        thumbnail = launch.episodeThumbnail, season = launch.seasonNumber, episode = launch.episodeNumber) }
    val episodes = remember(meta, launch) { meta?.videos.orEmpty().ifEmpty { listOf(fallback) } }
    val seasons = remember(episodes) { episodes.mapNotNull { it.season }.distinct().sorted() }
    var season by remember(launch) { mutableStateOf(launch.seasonNumber ?: seasons.firstOrNull() ?: 1) }
    val isSeries = launch.seasonNumber != null || seasons.isNotEmpty()
    val visibleEpisodes = remember(episodes, season, isSeries) {
        if (isSeries) eligibleDownloadEpisodes(episodes, season) else listOf(fallback)
    }
    val existing = downloads.items.filter { it.parentMetaId == (launch.parentMetaId ?: launch.videoId) }
    val existingIds = existing.mapTo(mutableSetOf()) { it.videoId }
    var selectedIds by remember(launch) { mutableStateOf<Set<String>>(setOf(launch.videoId).filterTo(linkedSetOf()) { it !in existingIds }) }
    val preferences = remember(launch.profileId, profile.activeProfile?.id) {
        runCatching { Json.decodeFromString<DownloadPreferences>(DownloadsStorage.loadPreferences().orEmpty()) }.getOrDefault(DownloadPreferences())
    }
    var quality by remember(preferences) { mutableStateOf(preferences.quality) }
    var wifiOnly by remember(preferences) { mutableStateOf(preferences.wifiOnly) }
    var sourceResults by remember(launch, profile.activeProfile?.id) { mutableStateOf<Map<String, List<StreamItem>>>(emptyMap()) }
    var audioChecks by remember(launch) { mutableStateOf<Map<StreamItem, Boolean>>(emptyMap()) }
    var englishChoices by remember(launch) { mutableStateOf<Map<Pair<String, DownloadQuality>, StreamItem?>>(emptyMap()) }
    var overrides by remember(launch) { mutableStateOf<Map<String, StreamItem>>(emptyMap()) }
    var expandedSource by remember { mutableStateOf<String?>(null) }
    var queuedCount by remember { mutableStateOf<Int?>(null) }
    var replacementId by remember { mutableStateOf<String?>(null) }
    var confirmReplaceId by remember { mutableStateOf<String?>(null) }
    var retryVersion by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val selected = visibleEpisodes.filter { it.id in selectedIds && (it.id !in existingIds || replacementId == it.id) }
    val selectedKey = selected.map { it.id }
    val isPreparing = selected.any { (it.id to quality) !in englishChoices }
    LaunchedEffect(quality, wifiOnly, ownerMatches) {
        if (ownerMatches) DownloadsStorage.savePreferences(Json.encodeToString(DownloadPreferences(quality, wifiOnly)))
    }
    LaunchedEffect(selectedKey, quality, ownerMatches, retryVersion) {
        if (!ownerMatches) return@LaunchedEffect
        val missing = selected.filter { (it.id to quality) !in englishChoices }
        if (missing.isEmpty()) return@LaunchedEffect
        coroutineScope {
                val permits = Semaphore(2)
                missing.map { video -> async {
                    permits.withPermit {
                        val sources = sourceResults[video.id] ?: withContext(Dispatchers.Default) {
                            try { resolveDownloadStreams(launch.type, video.id, video.season, video.episode)
                                .filter { it.isDirectFileDownloadSource } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { emptyList() }
                        }
                        if (!ownsSelection()) throw CancellationException("Download profile changed")
                        sourceResults = sourceResults + (video.id to sources)
                        val english = withTimeoutOrNull((20_000L + retryVersion.coerceIn(0, 3) * 15_000L).coerceAtMost(60_000L)) {
                            chooseEnglishDownloadSource(sources, quality, maxCandidates = 5 + retryVersion.coerceIn(0, 3) * 5) { candidate ->
                                audioChecks[candidate] ?: confirmDownloadEnglishAudio(candidate).also { confirmed ->
                                    if (!ownsSelection()) throw CancellationException("Download profile changed")
                                    audioChecks = audioChecks + (candidate to confirmed)
                                }
                            }
                        }
                        if (!ownsSelection()) throw CancellationException("Download profile changed")
                        englishChoices = englishChoices + ((video.id to quality) to english)
                    }
                } }.awaitAll()
        }
    }
    val chosen = selected.associate { video -> video.id to (overrides[video.id]?.takeIf { audioChecks[it] == true && it in sourceResults[video.id].orEmpty() }
        ?: englishChoices[video.id to quality]) }
    val ready = selected.filter { chosen[it.id] != null }
    val unavailable = selected.count { (it.id to quality) in englishChoices && chosen[it.id] == null }
    val knownSizes = ready.mapNotNull { chosen[it.id]?.behaviorHints?.videoSize?.takeIf { size -> size > 0 } }
    val estimatedBytes = knownSizes.fold(0L) { total, bytes -> if (Long.MAX_VALUE - total >= bytes) total + bytes else Long.MAX_VALUE }
    val pendingBytes = downloads.items.filter { it.status == DownloadStatus.Downloading }.sumOf { ((it.totalBytes ?: 0) - it.downloadedBytes).coerceAtLeast(0) }
    val freeBytes = remember(downloads.items, queuedCount) { DownloadsStorage.availableBytes()?.let { (it - pendingBytes).coerceAtLeast(0) } }
    val enoughSpace = freeBytes == null || estimatedBytes <= (freeBytes - 100L * 1024 * 1024).coerceAtLeast(0)

    if (confirmReplaceId != null) AlertDialog(onDismissRequest = { confirmReplaceId = null },
        title = { Text("Replace this download?") },
        text = { Text("The existing file will be removed when you start the replacement. You can keep it and play it from Downloads.") },
        confirmButton = { TextButton(onClick = { replacementId = confirmReplaceId; selectedIds = setOfNotNull(confirmReplaceId); confirmReplaceId = null }) { Text("Choose replacement") } },
        dismissButton = { TextButton(onClick = { confirmReplaceId = null }) { Text("Keep existing") } })

    Column(Modifier.fillMaxSize()) {
        NuvioScreenHeader(title = if (queuedCount != null) "Added to downloads" else "Download", onBack = onBack,
            modifier = Modifier.padding(horizontal = 12.dp))
        if (!ownerMatches) {
            Text("Your profile changed. Close this picker and open Download again.", Modifier.padding(24.dp))
            return@Column
        }
        val completedBatch = queuedCount
        if (completedBatch != null) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(if (completedBatch == 1) "1 download queued" else "$completedBatch episodes queued", style = MaterialTheme.typography.headlineSmall)
                Text("Two files download at a time. ${if (wifiOnly) "Downloads wait for Wi-Fi." else "Your current connection will be used."} You can keep browsing or leave the app.")
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Keep browsing") }
                OutlinedButton(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth()) { Text("View downloads") }
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(launch.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (isSeries) "Choose episodes and quality. Files with confirmed English audio are picked automatically. Saved episodes are kept." else "Choose a quality. A file with confirmed English audio is picked automatically.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DownloadQuality.entries.forEach { option -> FilterChip(selected = quality == option,
                        onClick = { quality = option; overrides = emptyMap() }, label = { Text(option.label) }) }
                }
                Text(quality.hint, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                    Text("Wi-Fi only")
                }
            }
            if (isSeries) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        seasons.forEach { number -> FilterChip(selected = season == number, onClick = { season = number; replacementId = null; selectedIds = emptySet() },
                            label = { Text(if (number == 0) "Specials" else "Season $number") }) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { replacementId = null; selectedIds = visibleEpisodes.filter { it.id !in existingIds }.mapTo(linkedSetOf()) { it.id } }) { Text("Whole season") }
                        OutlinedButton(onClick = {
                            replacementId = null
                            val watchedIds = visibleEpisodes.filter { WatchedRepository.isWatched(launch.parentMetaId ?: launch.videoId,
                                launch.parentMetaType ?: launch.type, it.season, it.episode) }.mapTo(mutableSetOf()) { it.id }
                            selectedIds = nextUnwatchedDownloads(visibleEpisodes, existingIds, watchedIds)
                        }) { Text("Next 3 unwatched") }
                        TextButton(onClick = { replacementId = null; selectedIds = emptySet() }) { Text("Clear") }
                    }
                    Text("${selected.size} of ${visibleEpisodes.size} available episodes selected", style = MaterialTheme.typography.labelMedium)
                }
            }
            items(visibleEpisodes, key = { it.id }) { video ->
                val saved = existing.firstOrNull { it.videoId == video.id }
                val protected = saved != null && replacementId != video.id
                val selectedVideo = video.id in selectedIds && !protected
                val source = chosen[video.id]
                Surface(shape = MaterialTheme.shapes.medium, color = if (selectedVideo) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSeries) Checkbox(checked = selectedVideo || protected, enabled = !protected,
                                onCheckedChange = { check -> if (replacementId != video.id) replacementId = null; selectedIds = if (check) selectedIds + video.id else selectedIds - video.id })
                            Column(Modifier.weight(1f)) {
                                Text(if (isSeries) "E${video.episode} - ${video.title}" else launch.title, fontWeight = FontWeight.SemiBold)
                                Text(when {
                                    protected -> if (saved?.status == DownloadStatus.Completed) "Already downloaded" else "Already in Downloads"
                                    !selectedVideo -> "Not selected"
                                    (video.id to quality) !in englishChoices -> "Finding files and checking English audio..."
                                    source == null -> if (sourceResults[video.id].isNullOrEmpty()) "No downloadable files returned." else "Audio check inconclusive. Check more files below."
                                    else -> listOfNotNull("English audio checked", source.downloadResolution().takeIf { it > 0 }?.let { "${it}p" },
                                        source.behaviorHints.videoSize?.takeIf { it > 0 }?.let(::downloadSizeLabel), source.sourceName ?: source.addonName).joinToString(" - ")
                                }, style = MaterialTheme.typography.bodySmall)
                            }
                            if (selectedVideo && sourceResults[video.id].orEmpty().count { audioChecks[it] == true } > 1) TextButton(onClick = {
                                expandedSource = if (expandedSource == video.id) null else video.id
                            }) { Text("Change source (optional)") }
                        }
                        if (expandedSource == video.id && selectedVideo) sourceResults[video.id].orEmpty().filter { audioChecks[it] == true }.forEach { option ->
                            Row(Modifier.fillMaxWidth().clickable { overrides = overrides + (video.id to option); expandedSource = null }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = source === option, onClick = { overrides = overrides + (video.id to option); expandedSource = null })
                                Column(Modifier.weight(1f)) {
                                    Text(option.streamLabel, maxLines = 2)
                                    Text(listOfNotNull(option.behaviorHints.videoSize?.takeIf { it > 0 }?.let(::downloadSizeLabel), option.sourceName ?: option.addonName).joinToString(" - "), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (protected) Row {
                            TextButton(onClick = if (saved?.status == DownloadStatus.Completed) onOpenSavedDownloads else onOpenDownloads) { Text("View downloads") }
                            TextButton(onClick = { confirmReplaceId = video.id }) { Text("Replace file") }
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isPreparing) Text("Checking English audio - ${selected.count { (it.id to quality) in englishChoices }} of ${selected.size} checked")
                Text("${ready.size} ready" + if (estimatedBytes > 0) " - About ${downloadSizeLabel(estimatedBytes)}" else " - Size not supplied")
                if (knownSizes.size < ready.size) Text("${ready.size - knownSizes.size} file sizes are unknown", style = MaterialTheme.typography.bodySmall)
                freeBytes?.let { Text("${downloadSizeLabel(it)} available after queued downloads", style = MaterialTheme.typography.bodySmall) }
                if (unavailable > 0) {
                    Text("Audio not confirmed for $unavailable ${if (unavailable == 1) "episode" else "episodes"}. These stay out of this batch.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { sourceResults = sourceResults.filterValues { it.isNotEmpty() }
                        englishChoices = englishChoices.filterValues { it != null }
                        if (retryVersion >= 3) { audioChecks = audioChecks.filterValues { it }; retryVersion = 0 } else retryVersion++ }) { Text(if (retryVersion >= 3) "Retry audio checks" else "Check more files for English audio") }
                }
                if (!enoughSpace) Text("Not enough free space for the known file sizes. Choose Small or fewer episodes.", color = MaterialTheme.colorScheme.error)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = !isPreparing && ready.isNotEmpty() && enoughSpace && ownerMatches,
                    modifier = Modifier.fillMaxWidth(), onClick = {
                        if (!ownsSelection()) { message = "Your profile changed. Reopen Download."; return@Button }
                        var added = 0
                        ready.forEach { video ->
                            val result = DownloadsRepository.enqueueFromStream(launch.type, video.id,
                                launch.parentMetaId ?: launch.videoId, launch.parentMetaType ?: launch.type, launch.title,
                                launch.logo, launch.poster, launch.background, video.season, video.episode,
                                video.title.takeIf { isSeries }, video.thumbnail, chosen.getValue(video.id)!!,
                                wifiOnly = wifiOnly, replaceExisting = replacementId == video.id && ready.size == 1)
                            if (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) added++
                        }
                        if (added > 0) queuedCount = added else message = "These files are already in Downloads."
                    }) { Text(if (ready.size == 1) "Download${if (isSeries) " 1 episode" else " movie"}" else "Download ${ready.size} episodes") }
            }
        }
    }
}
