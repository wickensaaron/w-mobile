package com.nuvio.app.features.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.format.formatLocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
internal fun MobileLiveTvRecordingSetup(profileId: Int, nowMs: Long,
    selection: Pair<LiveTvChannel, LiveTvProgramme>?,
    recordNowSelection: Pair<LiveTvChannel, LiveTvProgramme?>?,
    onClose: () -> Unit, onOpenList: () -> Unit,
    onPlay: (LiveTvRecording, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember(profileId) { mutableStateOf(false) }
    var error by remember(profileId) { mutableStateOf<String?>(null) }
    var result by remember(profileId) { mutableStateOf<LiveTvRecording?>(null) }
    var seriesRule by remember(profileId) { mutableStateOf<CoreRecordingSeriesRule?>(null) }
    var recordSeries by remember(selection) { mutableStateOf(false) }
    var protectSport by remember(selection) { mutableStateOf(false) }
    var recordNowMode by remember(recordNowSelection) { mutableStateOf(RecordNowMode.ThirtyMinutes) }
    var recordThisChannelNow by remember(selection) { mutableStateOf(false) }
    var playbackStopped by remember(recordNowSelection) { mutableStateOf(false) }
    val activeSelection = selection ?: recordNowSelection
    val isRecordNow = recordNowSelection != null || recordThisChannelNow
    val submitted = result != null || seriesRule != null
    var coreProviders by remember(profileId, activeSelection) { mutableStateOf<List<CoreRecordingProvider>>(emptyList()) }
    var selectedCoreProviderId by remember(profileId, activeSelection) { mutableStateOf<String?>(null) }
    var selectedAlternativeStreamId by remember(profileId, activeSelection, selectedCoreProviderId) { mutableStateOf<String?>(null) }
    var coreGuide by remember(profileId, activeSelection, selectedCoreProviderId, selectedAlternativeStreamId) { mutableStateOf<CoreRecordingGuide?>(null) }
    var guideLoading by remember(profileId, activeSelection, selectedCoreProviderId, selectedAlternativeStreamId) { mutableStateOf(false) }
    var guideError by remember(profileId, activeSelection, selectedCoreProviderId, selectedAlternativeStreamId) { mutableStateOf<String?>(null) }
    var coreAlternatives by remember(profileId, activeSelection, selectedCoreProviderId) {
        mutableStateOf<List<CoreRecordingAlternative>>(emptyList())
    }
    var selectedCoreProgramme by remember(profileId, activeSelection, selectedCoreProviderId, selectedAlternativeStreamId) {
        mutableStateOf<CoreRecordingProgramme?>(null)
    }
    val sourceValid = activeSelection?.first?.let { runCatching { exactXtreamRecordingStreamId(it) }.isSuccess } == true
    val selectedCoreProvider = coreProviders.firstOrNull { it.id == selectedCoreProviderId }
    val selectedAlternative = coreAlternatives.firstOrNull { it.streamId == selectedAlternativeStreamId }
    val recordingChannel = activeSelection?.first?.let { original ->
        selectedAlternative?.let { alternative ->
            LiveTvRepository.uiState.value.channels.firstOrNull {
                it.playlistId == original.playlistId && it.id.substringAfterLast(':') == alternative.streamId &&
                    it.guideId == alternative.guideId && it.name == alternative.channelName
            }
        } ?: original
    }
    val guideIdentityMatches = coreGuide?.guideId?.isNotBlank() == true &&
        coreGuide?.guideId.equals(recordingChannel?.guideId, ignoreCase = true)
    val localProgramme = activeSelection?.second?.takeIf { programme ->
        guideIdentityMatches && coreGuide?.programmes?.any {
            it.title == programme.title && it.startMs == programme.startEpochMs && it.endMs == programme.stopEpochMs
        } == true
    }
    val coreProgramme = selectedCoreProgramme?.takeIf { selected ->
        guideIdentityMatches && coreGuide?.programmes?.contains(selected) == true
    }?.let { selected ->
        LiveTvProgramme(recordingChannel?.guideId.orEmpty(), selected.title, startEpochMs = selected.startMs, stopEpochMs = selected.endMs)
    }
    val chosenProgramme = coreProgramme ?: localProgramme
    val guideProgrammeMatches = chosenProgramme != null
    val canSchedule = chosenProgramme?.startEpochMs?.let { it > Clock.System.now().toEpochMilliseconds() } == true
    val futureCoreProgrammes = coreGuide?.programmes.orEmpty()
        .filter { it.startMs > Clock.System.now().toEpochMilliseconds() }.take(20)

    LaunchedEffect(activeSelection, profileId) { result = null; seriesRule = null; error = null }
    LaunchedEffect(activeSelection, profileId, sourceValid) {
        if (activeSelection != null) {
            if (!sourceValid) {
                error = "This recording option currently supports Xtream channels only."
            } else {
                try {
                    coreProviders = LiveTvRecordingClient.coreProviders()
                    if (coreProviders.isEmpty() && !submitted) {
                        error = "Add this Xtream provider to W Core before recording."
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (!submitted) error = failure.message ?: "Could not load recording sources."
                }
            }
        }
    }
    LaunchedEffect(activeSelection, selectedCoreProviderId, sourceValid) {
        val channel = activeSelection?.first ?: return@LaunchedEffect
        val coreId = selectedCoreProviderId ?: return@LaunchedEffect
        coreAlternatives = try {
            LiveTvRecordingClient.alternatives(coreId, exactXtreamRecordingStreamId(channel)).filter { alternative -> LiveTvRepository.uiState.value.channels.any { it.playlistId == channel.playlistId && it.id.substringAfterLast(':' ) == alternative.streamId && it.guideId == alternative.guideId && it.name == alternative.channelName } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
    LaunchedEffect(activeSelection, selectedCoreProviderId, selectedAlternativeStreamId, sourceValid) {
        val channel = recordingChannel ?: return@LaunchedEffect
        val coreId = selectedCoreProviderId ?: return@LaunchedEffect
        guideLoading = true
        try {
            val streamId = exactXtreamRecordingStreamId(channel)
            coreGuide = LiveTvRecordingClient.coreGuide(coreId, streamId)
            guideError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            coreGuide = null
            guideError = failure.message ?: "Could not check this channel's W Core guide."
        } finally {
            guideLoading = false
        }
    }

    if (activeSelection != null) AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (submitted) "Recording request" else if (isRecordNow) "Record now?" else "Record this programme?") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (isRecordNow) "Record ${activeSelection.first.name} from now"
                    else chosenProgramme?.title ?: activeSelection.second?.title ?: activeSelection.first.name)
                chosenProgramme?.takeUnless { isRecordNow }?.let {
                    Text("${activeSelection.first.name} · ${formatLocalDateTime(it.startEpochMs)} – ${formatLocalDateTime(it.stopEpochMs)}")
                }
                if (busy) Text("W Core is checking this channel…")
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("record-error")) }
                result?.let { Text(when (it.status) {
                    "scheduled" -> if (isRecordNow) "W Core accepted the request. Refresh recordings to check when capture starts." else "Scheduled in W Core."
                    else -> "Status: ${it.status}"
                }) }
                seriesRule?.let { Text("Series link is on for ${it.programmeTitle}. W Core will look for future airings on ${it.channelName}.") }
                if (!submitted) {
                    Text("Your IPTV source: ${activeSelection.first.playlistName ?: "Xtream"}")
                    Text("Choose the matching W Core source. W Core checks channel and guide IDs, but the IPTV provider may still send the wrong programme.")
                    coreProviders.forEach { provider ->
                        TextButton(onClick = { selectedCoreProviderId = provider.id },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("${if (selectedCoreProviderId == provider.id) "●" else "○"} ${provider.name}")
                        }
                    }
                    val selectedName = selectedCoreProvider?.name
                    if (selectedName != null &&
                        !selectedName.equals(activeSelection.first.playlistName, ignoreCase = true)) {
                        Text("The source names differ. Confirm they use the same IPTV subscription before recording.")
                    }
                    if (coreAlternatives.size > 1) {
                        Text("Channel feed")
                        Text("If a feed shows a green or damaged picture, choose another listed feed before recording. Check the picture yourself; matching guide details cannot prove its video is correct.")
                        coreAlternatives.forEach { alternative ->
                            TextButton(onClick = { selectedAlternativeStreamId = alternative.streamId },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                val currentId = runCatching { exactXtreamRecordingStreamId(activeSelection.first) }.getOrNull()
                                Text("${if ((selectedAlternativeStreamId ?: currentId) == alternative.streamId) "●" else "○"} " +
                                    "${alternative.channelName} · feed ${alternative.streamId}")
                            }
                        }
                    }
                    if (guideLoading) Text("Checking this channel's W Core guide…")
                    guideError?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                    if (coreGuide != null && !guideProgrammeMatches) {
                        Text(if (isRecordNow) "W Core has no exact guide match for this programme. Choose a fixed duration."
                            else if (futureCoreProgrammes.isEmpty())
                                recordingGuideUnavailableMessage(coreGuide?.unavailableReason)
                            else "The programme you selected is not in W Core's guide. Choose an exact airing below.")
                        if (!isRecordNow) TextButton(onClick = { recordThisChannelNow = true }) {
                            Text("Record this channel now instead")
                        }
                    }
                    if (!isRecordNow && guideIdentityMatches && coreGuide != null) {
                        if (futureCoreProgrammes.isEmpty() && guideProgrammeMatches)
                            Text("W Core has no upcoming programmes for this feed.")
                        else if (futureCoreProgrammes.isNotEmpty() &&
                            (localProgramme == null || selectedCoreProgramme != null)) {
                            Text("Upcoming programmes on this feed")
                            LazyColumn(Modifier.heightIn(max = 160.dp)) {
                                items(futureCoreProgrammes, key = { "${it.title}:${it.startMs}" }) { programme ->
                                    TextButton(onClick = { selectedCoreProgramme = programme },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text("${if (selectedCoreProgramme == programme) "●" else "○"} " +
                                            "${programme.title} · ${formatLocalDateTime(programme.startMs)}")
                                    }
                                }
                            }
                        }
                    }
                    if (!isRecordNow && guideProgrammeMatches && !canSchedule) {
                        Text("This airing has already started. Record the channel now instead.")
                        TextButton(onClick = { recordThisChannelNow = true }) { Text("Record this channel now instead") }
                    }
                    if (isRecordNow) {
                        if (recordThisChannelNow) Text("This starts now; it will not wait for the programme shown in the guide.")
                        Text("Choose when W Core should stop:")
                        RecordNowMode.entries.filter { it != RecordNowMode.ProgrammeEnd ||
                            guideProgrammeMatches &&
                            chosenProgramme?.let { p -> p.startEpochMs <= nowMs && p.stopEpochMs > nowMs &&
                                p.stopEpochMs - nowMs <= 6 * 60 * 60 * 1000L } == true
                        }.forEach { mode ->
                            TextButton(onClick = { recordNowMode = mode }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("${if (recordNowMode == mode) "●" else "○"} ${mode.label}")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = playbackStopped, onCheckedChange = { playbackStopped = it },
                                modifier = Modifier.testTag("record-now-playback-stopped"))
                            Spacer(Modifier.width(10.dp))
                            Text("I stopped watching this IPTV source")
                        }
                        Text("This account allows one connection. W Core cannot record while another device is using it for live playback.")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = recordSeries, onCheckedChange = { recordSeries = it })
                            Spacer(Modifier.width(10.dp))
                            Text("Record this series")
                        }
                        if (recordSeries) Text("Record future guide airings with this exact title on this channel. Repeats may be included. You can turn off the series link in Library.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = protectSport, onCheckedChange = { protectSport = it }, enabled = !recordSeries,
                                modifier = Modifier.testTag("record-protect-sport"))
                            Spacer(Modifier.width(10.dp))
                            Text("Protect live sport")
                        }
                        if (!recordSeries) Text(if (protectSport) "Start 5 minutes early and allow 90 extra minutes. You can add 30 more minutes later."
                            else "Start 2 minutes early and allow 5 extra minutes.")
                        Text("If your IPTV account allows one connection, stop watching it before this recording starts.")
                    }
                }
            }
        },
        confirmButton = {
            if (!submitted) Button(enabled = !busy && sourceValid &&
                selectedCoreProviderId != null && (if (isRecordNow) playbackStopped &&
                    (recordNowMode != RecordNowMode.ProgrammeEnd || guideProgrammeMatches)
                    else guideProgrammeMatches && canSchedule),
                modifier = Modifier.testTag("record-confirm"), onClick = {
                busy = true; error = null
                scope.launch {
                    try { if (recordSeries && !isRecordNow) seriesRule = LiveTvRecordingClient.createSeries(profileId,
                        requireNotNull(recordingChannel), requireNotNull(chosenProgramme),
                        requireNotNull(selectedCoreProviderId))
                    else result = if (isRecordNow) LiveTvRecordingClient.recordNow(profileId,
                        requireNotNull(recordingChannel), chosenProgramme,
                        recordNowMode, requireNotNull(selectedCoreProviderId),
                        Clock.System.now().toEpochMilliseconds())
                    else LiveTvRecordingClient.schedule(profileId,
                        requireNotNull(recordingChannel), requireNotNull(chosenProgramme),
                        protectSport, requireNotNull(selectedCoreProviderId)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Could not schedule recording." }
                    finally {
                        busy = false
                        if (result == null && seriesRule == null && error == null) {
                            error = "W Core did not confirm this request. Check Recordings before retrying."
                        }
                    }
                }
            }) { Text(if (busy) "Requesting…" else if (isRecordNow) "Start recording" else if (recordSeries) "Record series" else "Record") }
            else Button(onClick = { onClose(); onOpenList() }) { Text("View recordings") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Close") } },
    )

}
