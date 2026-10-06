package com.nuvio.app.features.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.userId
import com.nuvio.app.core.network.WCoreConnectionRepository
import com.nuvio.app.core.network.WCoreConnectionStatus
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Clock

internal data class RecordingShow(val title: String, val items: List<LiveTvRecording>)
internal fun recordingShows(recordings: List<LiveTvRecording>): List<RecordingShow> = recordings
    .filter { it.status !in setOf("cancelled", "deleted") }
    .sortedByDescending { it.startMs }
    .groupBy { it.seriesTitle?.takeIf(String::isNotBlank) ?: it.title.removePrefix("Record Now: ").trim() }
    .map { (title, episodes) -> RecordingShow(title, episodes) }

@Composable
internal fun LibraryRecordingsRow(profileId: Int, onPlay: ((LiveTvRecording, String) -> Unit)?) {
    val auth by AuthRepository.state.collectAsStateWithLifecycle()
    val origin by WCoreConnectionRepository.configuredOrigin.collectAsStateWithLifecycle()
    key(auth.userId, profileId, origin) { OwnedLibraryRecordingsRow(profileId, onPlay) }
}

@Composable
private fun OwnedLibraryRecordingsRow(profileId: Int, onPlay: ((LiveTvRecording, String) -> Unit)?) {
    val connection by WCoreConnectionRepository.status.collectAsStateWithLifecycle()
    var recordings by remember { mutableStateOf<List<LiveTvRecording>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var open by remember { mutableStateOf(false) }
    ScreenActivityEffect(profileId, connection, open) { active ->
        if (!active || open) return@ScreenActivityEffect
        if (connection != WCoreConnectionStatus.Connected) { recordings = emptyList(); loading = false; return@ScreenActivityEffect }
        while (true) {
            try { recordings = LiveTvRecordingClient.list(profileId); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not load recordings." }
            finally { loading = false }
            delay(30_000)
        }
    }
    if (connection != WCoreConnectionStatus.Connected) return
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Recordings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.nuvio.colors.textPrimary)
            Text("Your W Core recordings", color = MaterialTheme.nuvio.colors.textSecondary)
            TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("View and manage recordings") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!loading && recordings.isEmpty() && error == null) Text("Record a programme from the TV guide to see it here.", color = MaterialTheme.nuvio.colors.textSecondary)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(recordingShows(recordings), key = { it.title }) { show ->
                OutlinedCard(onClick = { open = true }, modifier = Modifier.width(180.dp).heightIn(min = 100.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(show.title, style = MaterialTheme.typography.titleSmall)
                        Text("${show.items.size} recording${if (show.items.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                        Text(recordingStatusLabel(show.items.first().status), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (open) MobileLiveTvRecordings(profileId, Clock.System.now().toEpochMilliseconds(), null, null,
        onClose = { open = false }, onOpenList = {}, onPlay = { recording, url ->
            open = false
            if (onPlay != null) onPlay(recording, url) else LiveTvRecordingPlaybackRequests.request(recording, url)
        })
}
