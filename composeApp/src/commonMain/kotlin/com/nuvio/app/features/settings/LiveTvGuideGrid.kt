package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.livetv.LiveTvAccountGuideRow
import com.nuvio.app.features.livetv.LiveTvProgramme
import com.nuvio.app.features.livetv.filterLiveTvAccountGuideRows
import com.nuvio.app.features.livetv.isLiveTvGuideRowFavourite
import com.nuvio.app.features.livetv.LiveTvRepository
import com.nuvio.app.features.livetv.LiveTvUiState
import com.nuvio.app.core.format.formatLocalHourMinute
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import kotlinx.coroutines.delay
import kotlin.time.Clock

private const val halfHourMs = 30L * 60 * 1000
private const val guideHours = 6
private val halfHourWidth = 90.dp
private val channelWidth = 124.dp

@Composable
internal fun LiveTvGuideGrid(
    uiState: LiveTvUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    favoritesOnly: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onChannelsClick: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    val windowStart = now / halfHourMs * halfHourMs
    val windowEnd = windowStart + guideHours * 60L * 60 * 1000
    val scrollState = rememberScrollState()
    val prepared = rememberPreparedLiveTvGuide(uiState, now, favoritesOnly)
    val visibleRows = remember(prepared.presentation.rows, searchQuery) {
        filterLiveTvAccountGuideRows(prepared.presentation.rows, searchQuery)
    }
    val bottom = nuvioSafeBottomPadding(24.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
        contentPadding = PaddingValues(top = 20.dp, bottom = bottom),
    ) {
        item(key = "guide_controls") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("W MEDIA PLAYER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Live TV", style = MaterialTheme.typography.headlineMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChannelsClick) { Text("Sources & settings") }
                    OutlinedButton(onClick = LiveTvRepository::refreshGuide, enabled = uiState.hasGuideSources) { Text("Refresh") }
                    if (uiState.accountGuideOwner != null) {
                        OutlinedButton(onClick = {
                            val owner = uiState.accountGuideOwner ?: return@OutlinedButton
                            runCatching { uriHandler.openUri("${owner.backend}/functions/v1/tv-logins-exchange?organise=live-tv&profile=${owner.profile}") }
                        }) { Text("Organise channels") }
                        OutlinedButton(onClick = LiveTvRepository::restoreAccountGuidePreferences,
                            enabled = !uiState.isAccountGuideSyncing && !uiState.isAccountGuideSaving) { Text("Refresh choices") }
                    }
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = { Text("Search channels") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = if (searchQuery.isBlank()) null else {
                        { TextButton(onClick = { onSearchQueryChange("") }) { Text("Clear") } }
                    },
                )
                OutlinedButton(onClick = { onFavoritesOnlyChange(!favoritesOnly) }) {
                    Text(if (favoritesOnly) "Favourites · show all" else "Show favourites")
                }
                Text("${visibleRows.size} channels · " + if (uiState.accountGuideSnapshot?.preferences?.ukOnly != false) "UK guide" else "Programme guide",
                    style = MaterialTheme.typography.labelMedium)
                if (uiState.isLoading || uiState.isRestoringAccountSources) Text("Restoring channels…")
                if (uiState.isGuideLoading || prepared.isPreparing) Text("Preparing programme guide…")
                if (uiState.isAccountGuideSyncing) Text("Updating your guide choices…", style = MaterialTheme.typography.bodySmall)
                uiState.accountGuideSyncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                prepared.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                uiState.guideErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!uiState.hasGuideSources) Text("Add an XMLTV URL in Sources to populate the guide.")
                Text("Channels without programme data are hidden. Times are local; swipe for later programmes.", style = MaterialTheme.typography.bodySmall)
            }
        }
        stickyHeader(key = "guide_time_axis") {
            Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(top = 14.dp)) {
                Box(modifier = Modifier.width(channelWidth).height(42.dp))
                Row(modifier = Modifier.weight(1f).horizontalScroll(scrollState)) {
                    repeat(guideHours * 2) { slot ->
                        val slotTime = windowStart + slot * halfHourMs
                        Text(
                            formatLocalHourMinute(slotTime),
                            modifier = Modifier.width(halfHourWidth).padding(start = 6.dp, top = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        items(visibleRows, key = { "grid:${it.channel.id}" }) { row ->
            LiveTvGridChannelRow(row, prepared.programmesByChannelId[row.channel.id].orEmpty(), uiState,
                now, windowStart, windowEnd, scrollState)
        }
        if (visibleRows.isEmpty() && !prepared.isPreparing && !uiState.isLoading && !uiState.isGuideLoading && !uiState.isRestoringAccountSources) {
            item(key = "guide_empty") {
                Text(if (favoritesOnly) "No favourites with programme data match this search. Star a channel to save it."
                    else "No channels with programme data match your choices. Refresh the guide or update hidden categories in your organiser.",
                    modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun LiveTvGridChannelRow(
    row: LiveTvAccountGuideRow,
    programmes: List<LiveTvProgramme>,
    uiState: LiveTvUiState,
    now: Long,
    windowStart: Long,
    windowEnd: Long,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    val channel = row.channel
    val favourite = if (channel.accountScope == null) channel.id in uiState.favoriteChannelIds
        else isLiveTvGuideRowFavourite(row, uiState.favoriteChannelIds, uiState.programmes, now)
    val totalWidth = halfHourWidth * (guideHours * 2)
    Row(modifier = Modifier.fillMaxWidth().height(80.dp)) {
        Box(
            modifier = Modifier.width(channelWidth).fillMaxHeight().clickable { LiveTvRepository.requestPlayback(channel) }.padding(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.channelNumber?.let { "$it · ${row.displayName}" } ?: row.displayName,
                    style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { LiveTvRepository.toggleFavoriteGuideRow(row) },
                    enabled = channel.accountScope == null || (!uiState.isAccountGuideSaving && !uiState.isAccountGuideSyncing && uiState.accountGuideSnapshot != null),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.semantics { contentDescription = if (favourite) "Remove ${row.displayName} from favourites" else "Add ${row.displayName} to favourites" }) {
                    Text(if (favourite) "★" else "☆", color = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(scrollState)) {
            Box(modifier = Modifier.width(totalWidth).height(80.dp)) {
                Row(modifier = Modifier.fillMaxSize()) {
                    repeat(guideHours * 2) {
                        Box(modifier = Modifier.width(halfHourWidth).fillMaxHeight().border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)))
                    }
                }
                programmes.filter { it.startEpochMs < windowEnd && it.stopEpochMs > windowStart }.forEach { programme ->
                    val start = maxOf(programme.startEpochMs, windowStart)
                    val end = minOf(programme.stopEpochMs, windowEnd)
                    val x = ((start - windowStart).toFloat() / halfHourMs * halfHourWidth.value).dp
                    val width = (((end - start).toFloat() / halfHourMs * halfHourWidth.value) - 3f).coerceAtLeast(18f).dp
                    val onNow = programme.startEpochMs <= now && programme.stopEpochMs > now
                    Box(
                        modifier = Modifier
                            .offset(x = x, y = 4.dp)
                            .width(width)
                            .height(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (onNow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { LiveTvRepository.requestPlayback(channel) }
                            .padding(8.dp),
                    ) {
                        Text(
                            programme.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (onNow) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (onNow) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
