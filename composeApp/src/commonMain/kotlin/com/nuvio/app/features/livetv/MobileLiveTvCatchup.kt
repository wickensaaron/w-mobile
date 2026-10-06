package com.nuvio.app.features.livetv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlin.time.Clock

@Composable
internal fun MobileLiveTvCatchup(selection: LiveTvCatchupSelection, onClose: () -> Unit) {
    val clock = MobileLiveTvArchiveClock
    val loader = remember { LiveTvCatchupLoader(clock) }
    var zone by remember(selection) { mutableStateOf<String?>(null) }
    var days by remember(selection) { mutableStateOf<List<String>>(emptyList()) }
    var day by remember(selection) { mutableStateOf<String?>(null) }
    var history by remember(selection) { mutableStateOf<LiveTvArchiveHistory?>(null) }
    var status by remember(selection) { mutableStateOf<LiveTvArchiveStatus?>(null) }
    var loading by remember(selection) { mutableStateOf(true) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(selection, retry) {
        loading = true
        history = null
        if (selection.archive.retentionDays == null) {
            status = LiveTvArchiveStatus.UNKNOWN_RETENTION
            loading = false
            return@LaunchedEffect
        }
        val result = loader.timezone(selection) { LiveTvRepository.ownsCatchup(selection) }
        zone = result.zone
        status = result.status
        val available = result.zone?.let {
            clock.days(Clock.System.now().toEpochMilliseconds(), selection.archive.retentionDays ?: 0, it)
        }.orEmpty()
        days = available
        day = day?.takeIf { it in available } ?: available.firstOrNull()
        loading = false
    }
    LaunchedEffect(selection, zone, day, retry) {
        val zoneValue = zone ?: return@LaunchedEffect
        val selectedDay = day ?: return@LaunchedEffect
        loading = true
        history = null
        val result = loader.history(selection, zoneValue, selectedDay, Clock.System.now().toEpochMilliseconds()) {
            LiveTvRepository.ownsCatchup(selection)
        }
        history = result
        status = result.status
        loading = false
    }
    fun play(programme: LiveTvArchiveProgramme, startOver: Boolean, resume: Boolean = false) {
        val loaded = history ?: return
        val request = LiveTvCatchupRequest.fromHistory(loaded, programme, Clock.System.now().toEpochMilliseconds(), startOver)
        if (request != null && LiveTvRepository.ownsCatchup(selection)) {
            LiveTvRepository.requestCatchup(request, resume)
            onClose()
        } else status = LiveTvArchiveStatus.STALE_SELECTION
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f).safeDrawingPadding(),
            color = MaterialTheme.colorScheme.background) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to guide") }
                    Text(selection.channel.name, style = MaterialTheme.typography.headlineSmall)
                    Text("Catch-up", style = MaterialTheme.typography.titleMedium)
                    zone?.let { Text("Provider time: $it", style = MaterialTheme.typography.bodySmall) }
                }
                item {
                    OutlinedButton(onClick = {
                        if (LiveTvRepository.ownsCatchup(selection)) LiveTvRepository.requestPlayback(selection.channel)
                        onClose()
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Watch live") }
                }
                if (days.isNotEmpty()) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(days) { date ->
                            FilterChip(selected = date == day, onClick = { day = date },
                                label = { Text(date) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
                item {
                    Text(if (loading) "Loading recorded programmes…" else status?.let(::liveTvArchiveMessage).orEmpty())
                    if (!loading && status != LiveTvArchiveStatus.SUCCESS) {
                        TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                    }
                }
                if (!loading) {
                    history?.airing?.let { airing -> item {
                        Text(airing.title, style = MaterialTheme.typography.titleMedium)
                        Text("Only the portion recorded so far is available. Return to the guide to watch live.")
                        Button(onClick = { play(airing, true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Watch from start")
                        }
                    } }
                    items(history?.programmes.orEmpty(), key = { "${it.startMs}:${it.endMs}:${it.title}" }) { programme ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(programme.title, style = MaterialTheme.typography.titleMedium)
                                Text("${clock.localTimestamp(programme.startMs, zone!!).substring(11, 16)} – ${clock.localTimestamp(programme.endMs, zone!!).substring(11, 16)}")
                                val request = history?.let { LiveTvCatchupRequest.fromHistory(it, programme, Clock.System.now().toEpochMilliseconds()) }
                                val progress = request?.let(::liveTvCatchupHistoryId)?.let { WatchProgressRepository.progressForVideo(it) }
                                if (progress != null && progress.isResumable && progress.lastPositionMs > 0) {
                                    Button(onClick = { play(programme, false, true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Resume") }
                                }
                                OutlinedButton(onClick = { play(programme, false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Watch from beginning") }
                            }
                        }
                    }
                }
            }
        }
    }
}
