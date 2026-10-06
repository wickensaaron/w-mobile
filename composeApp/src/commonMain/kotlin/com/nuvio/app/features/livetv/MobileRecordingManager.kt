package com.nuvio.app.features.livetv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.userId
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.core.network.WCoreConnectionRepository
import com.nuvio.app.core.network.WCoreConnectionStatus
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun MobileLiveTvRecordings(
    profileId: Int, nowMs: Long,
    selection: Pair<LiveTvChannel, LiveTvProgramme>?,
    recordNowSelection: Pair<LiveTvChannel, LiveTvProgramme?>?,
    onClose: () -> Unit, onOpenList: () -> Unit,
    onPlay: (LiveTvRecording, String) -> Unit,
) {
    val auth by AuthRepository.state.collectAsStateWithLifecycle()
    val origin by WCoreConnectionRepository.configuredOrigin.collectAsStateWithLifecycle()
    key(auth.userId, profileId, origin) {
        if (selection == null && recordNowSelection == null) {
            RecordingManager(profileId, onClose, onPlay)
        } else {
            MobileLiveTvRecordingSetup(profileId, nowMs, selection, recordNowSelection, onClose, onOpenList, onPlay)
        }
    }
}

internal fun recordingStatusLabel(status: String): String = when (status) {
    "ready" -> "Ready to watch"
    "scheduled" -> "Scheduled"
    "resolving" -> "Starting recording"
    "recording" -> "Recording now"
    "failed" -> "Recording failed"
    "cancelled" -> "Cancelled"
    else -> status.replaceFirstChar { it.uppercase() }
}

@Composable
private fun RecordingManager(profileId: Int, onClose: () -> Unit, onPlay: (LiveTvRecording, String) -> Unit) {
    val connection by WCoreConnectionRepository.status.collectAsStateWithLifecycle()
    var recordings by remember { mutableStateOf<List<LiveTvRecording>>(emptyList()) }
    var rules by remember { mutableStateOf<List<CoreRecordingSeriesRule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var seriesError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf<LiveTvRecording?>(null) }
    var removeRule by remember { mutableStateOf<CoreRecordingSeriesRule?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        val owner = RecordingRequestOwner(profileId)
        if (!owner.isCurrent()) { recordings = emptyList(); rules = emptyList(); loading = false; return }
        try {
            val fresh = LiveTvRecordingClient.list(profileId)
            if (owner.isCurrent()) { recordings = fresh; error = null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not load recordings." }
        try {
            val fresh = LiveTvRecordingClient.listSeries(profileId)
            if (owner.isCurrent()) { rules = fresh; seriesError = null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { seriesError = failure.message ?: "Could not load series links." }
        finally { loading = false }
    }
    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block(); refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not update recording." }
            finally { busy = false }
        }
    }
    ScreenActivityEffect(profileId, connection) { active ->
        if (!active) return@ScreenActivityEffect
        if (connection != WCoreConnectionStatus.Connected) {
            recordings = emptyList(); rules = emptyList(); loading = false
            return@ScreenActivityEffect
        }
        while (true) { refresh(); delay(15_000) }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.nuvio.colors.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                Text("Recordings", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.nuvio.colors.textPrimary,
                    modifier = Modifier.padding(top = 12.dp))
                Text("Your W Core recordings", color = MaterialTheme.nuvio.colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                    TextButton(onClick = { act { } }, enabled = !busy && connection == WCoreConnectionStatus.Connected,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) "Updating…" else "Refresh") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (connection != WCoreConnectionStatus.Connected) item { Text("Connect W Core in Settings to view and manage recordings.", color = MaterialTheme.nuvio.colors.textSecondary) }
                    if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    seriesError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    notice?.let { item { Text(it, color = MaterialTheme.nuvio.colors.textSecondary) } }
                    if (!loading && recordings.isEmpty() && rules.isEmpty() && error == null && connection == WCoreConnectionStatus.Connected) {
                        item { Text("No recordings yet. Choose a programme or Record now in the TV guide.", color = MaterialTheme.nuvio.colors.textSecondary) }
                    }
                    items(rules, key = { "rule:${it.id}" }) { rule ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(rule.programmeTitle, style = MaterialTheme.typography.titleMedium)
                                Text("Series link · ${rule.channelName}")
                                rule.lastError?.let { Text("A future airing needs attention. Check the guide or recording list.", color = MaterialTheme.colorScheme.error) }
                                TextButton(onClick = { removeRule = rule }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Turn off series link") }
                            }
                        }
                    }
                    items(recordings.filter { it.status != "deleted" }.sortedByDescending { it.startMs }, key = { it.id }) { recording ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(recording.seriesTitle ?: recording.title, style = MaterialTheme.typography.titleMedium)
                                recording.episodeTitle?.let { Text(it) }
                                if (recording.seasonNumber != null && recording.episodeNumber != null) Text("Season ${recording.seasonNumber} · Episode ${recording.episodeNumber}")
                                Text("${recording.channelName} · ${formatLocalDateTime(recording.startMs)}", style = MaterialTheme.typography.bodySmall)
                                Text(recordingStatusLabel(recording.status))
                                if (recording.status == "failed") Text(recordingFailureMessage(recording.errorCode), color = MaterialTheme.colorScheme.error)
                                if (recording.sourceFallbackUsed) Text("W Core used ${recording.channelName} after checking an alternate source.", style = MaterialTheme.typography.bodySmall)
                                if (recording.status == "ready") Button(enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                                    act {
                                        val owner = RecordingRequestOwner(profileId)
                                        val url = LiveTvRecordingClient.playbackUrl(profileId, recording.id)
                                        if (owner.isCurrent()) onPlay(recording, url)
                                    }
                                }) { Text("Play") }
                                if (recording.protectSport && recording.status in setOf("scheduled", "recording")) TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                                    act { LiveTvRecordingClient.extend(profileId, recording.id); notice = "Added 30 minutes." }
                                }) { Text("Add 30 minutes") }
                                if (recording.status in setOf("scheduled", "resolving", "recording")) TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                                    act { LiveTvRecordingClient.cancel(profileId, recording.id); notice = "Stop requested. W Core will update the recording shortly." }
                                }) { Text(if (recording.status == "recording") "Stop and save" else "Cancel recording") }
                                TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = { remove = recording }) { Text("Remove recording", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
    remove?.let { recording -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove recording?") },
        text = { Text("Remove ${recording.title} and its saved video? This cannot be undone.") },
        confirmButton = { TextButton(onClick = { remove = null; act { val pending = LiveTvRecordingClient.delete(profileId, recording.id); notice = if (pending) "Removed. W Core is finishing file cleanup." else "Recording removed." } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text("Keep") } }) }
    removeRule?.let { rule -> AlertDialog(onDismissRequest = { removeRule = null }, title = { Text("Turn off series link?") },
        text = { Text("Stop finding future airings of ${rule.programmeTitle}. Existing recordings remain.") },
        confirmButton = { TextButton(onClick = { removeRule = null; act { LiveTvRecordingClient.deleteSeries(profileId, rule.id); notice = "Series link removed." } }) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = { removeRule = null }) { Text("Keep") } }) }
}
