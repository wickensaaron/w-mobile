package com.nuvio.app.features.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.StreamsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_choose_source
import nuvio.composeapp.generated.resources.downloads_direct_file_only
import nuvio.composeapp.generated.resources.downloads_keep_app_open
import nuvio.composeapp.generated.resources.downloads_no_direct_files
import nuvio.composeapp.generated.resources.downloads_reported_size
import nuvio.composeapp.generated.resources.downloads_save_on_device
import nuvio.composeapp.generated.resources.downloads_source_changed
import nuvio.composeapp.generated.resources.downloads_source_unsupported
import org.jetbrains.compose.resources.stringResource

/** Explicit file selection. Opening this route never starts playback or an automatic download. */
@Composable
fun DownloadSourceScreen(
    launch: StreamLaunch,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val state by StreamsRepository.uiState.collectAsStateWithLifecycle()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val profileId = ProfileRepository.activeProfileId
    val activeProfileIndex = profileState.activeProfile?.profileIndex ?: profileId
    val ownerMatches = !launch.downloadOwnerUserId.isNullOrBlank() &&
        !launch.downloadOwnerProfileId.isNullOrBlank() &&
        activeProfileIndex == launch.profileId && profileId == launch.profileId &&
        profileState.activeProfile?.userId == launch.downloadOwnerUserId &&
        profileState.activeProfile?.id == launch.downloadOwnerProfileId
    val expectedRequestToken = remember(launch) {
        StreamsRepository.requestToken(
            type = launch.type,
            videoId = launch.videoId,
            season = launch.seasonNumber,
            episode = launch.episodeNumber,
            manualSelection = true,
        )
    }
    var loadStarted by remember(launch, activeProfileIndex, profileState.activeProfile?.userId, profileState.activeProfile?.id) { mutableStateOf(false) }
    val ownsRows = ownerMatches &&
        loadStarted && state.requestToken == expectedRequestToken
    val eligible = remember(state.groups, ownsRows) {
        if (ownsRows) state.groups.flatMap { it.streams }.filter(StreamItem::isDirectFileDownloadSource)
        else emptyList()
    }
    var selected by remember(launch.videoId) { mutableStateOf<StreamItem?>(null) }
    val selectedIsCurrent = selected?.let { choice -> eligible.any { it === choice } } == true
    val sourceChanged = stringResource(Res.string.downloads_source_changed)

    LaunchedEffect(launch, activeProfileIndex, profileId, profileState.activeProfile?.userId, profileState.activeProfile?.id) {
        loadStarted = false
        if (!ownerMatches) return@LaunchedEffect
        StreamsRepository.reload(
            type = launch.type,
            videoId = launch.videoId,
            parentMetaId = launch.parentMetaId,
            season = launch.seasonNumber,
            episode = launch.episodeNumber,
            manualSelection = true,
        )
        loadStarted = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        NuvioScreenHeader(
            title = stringResource(Res.string.downloads_choose_source),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            text = launch.episodeTitle?.takeIf { it.isNotBlank() } ?: launch.title,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(Res.string.downloads_direct_file_only),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (ownerMatches && (!loadStarted || (ownsRows && state.isAnyLoading && eligible.isEmpty()))) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
            }
            if (!ownerMatches || (loadStarted && !ownsRows) ||
                (ownsRows && eligible.isEmpty() && !state.isAnyLoading)) {
                item {
                    Text(
                        text = if (!ownerMatches || (loadStarted && !ownsRows))
                            stringResource(Res.string.downloads_source_changed)
                        else stringResource(Res.string.downloads_no_direct_files),
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(eligible, key = { index, source ->
                "$index|${source.addonId}"
            }) { _, source ->
                val checked = selected === source
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clickable { selected = source },
                    color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(14.dp),
                    border = if (checked) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = checked, onClick = { selected = source })
                        Column(modifier = Modifier.weight(1f).padding(start = 7.dp)) {
                            Text(
                                text = source.streamLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = source.sourceName?.takeIf { it.isNotBlank() } ?: source.addonName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            source.behaviorHints.videoSize?.takeIf { it > 0L }?.let { bytes ->
                                Text(
                                    text = stringResource(Res.string.downloads_reported_size, formatKnownDownloadSize(bytes)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(Res.string.downloads_keep_app_open),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        val choice = selected
                        val currentProfile = ProfileRepository.state.value.activeProfile
                        if (ProfileRepository.activeProfileId != launch.profileId ||
                            currentProfile?.userId != launch.downloadOwnerUserId ||
                            currentProfile?.id != launch.downloadOwnerProfileId ||
                            choice == null || !selectedIsCurrent) {
                            NuvioToastController.show(sourceChanged)
                            return@Button
                        }
                        val result = DownloadsRepository.enqueueFromStream(
                            contentType = launch.type,
                            videoId = launch.videoId,
                            parentMetaId = launch.parentMetaId ?: launch.videoId,
                            parentMetaType = launch.parentMetaType ?: launch.type,
                            title = launch.title,
                            logo = launch.logo,
                            poster = launch.poster,
                            background = launch.background,
                            seasonNumber = launch.seasonNumber,
                            episodeNumber = launch.episodeNumber,
                            episodeTitle = launch.episodeTitle,
                            episodeThumbnail = launch.episodeThumbnail,
                            stream = choice,
                        )
                        NuvioToastController.show(result.toastMessage())
                        if (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) {
                            onOpenDownloads()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedIsCurrent && ownsRows,
                ) {
                    Text(stringResource(Res.string.downloads_save_on_device))
                }
                Text(
                    text = stringResource(Res.string.downloads_source_unsupported),
                    modifier = Modifier.padding(top = 8.dp, bottom = nuvioSafeBottomPadding(2.dp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatKnownDownloadSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "${((bytes.toDouble() / (1024L * 1024L * 1024L)) * 10).toInt() / 10.0} GB"
    bytes >= 1024L * 1024L -> "${((bytes.toDouble() / (1024L * 1024L)) * 10).toInt() / 10.0} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
