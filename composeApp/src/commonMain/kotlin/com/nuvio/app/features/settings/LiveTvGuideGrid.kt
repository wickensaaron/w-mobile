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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.livetv.LiveTvRepository
import com.nuvio.app.features.livetv.LiveTvUiState
import com.nuvio.app.core.format.formatLocalHourMinute
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import kotlinx.coroutines.delay
import kotlin.time.Clock

private const val halfHourMs = 30L * 60 * 1000
private const val guideHours = 6
private val halfHourWidth = 90.dp
private val channelWidth = 108.dp

@Composable
internal fun LiveTvGuideGrid(
    uiState: LiveTvUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    favoritesOnly: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onChannelsClick: () -> Unit,
) {
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
    val visibleChannels = uiState.channels.filter { channel ->
        (!favoritesOnly || channel.id in uiState.favoriteChannelIds) &&
            (searchQuery.isBlank() || channel.name.contains(searchQuery.trim(), true) ||
                channel.group?.contains(searchQuery.trim(), true) == true)
    }
    val bottom = nuvioSafeBottomPadding(24.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
        contentPadding = PaddingValues(top = 20.dp, bottom = bottom),
    ) {
        item(key = "guide_controls") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live TV guide", style = MaterialTheme.typography.headlineMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChannelsClick) { Text("Channels") }
                    Button(onClick = {}) { Text("Guide") }
                    OutlinedButton(onClick = LiveTvRepository::refreshGuide, enabled = uiState.guideUrl.isNotBlank()) { Text("Refresh") }
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = { Text("Search channels") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedButton(onClick = { onFavoritesOnlyChange(!favoritesOnly) }) {
                    Text(if (favoritesOnly) "Show all" else "Favourites only")
                }
                if (uiState.isGuideLoading) Text("Loading programme guide…")
                uiState.guideErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (uiState.guideUrl.isBlank()) Text("Add an XMLTV URL in Sources to populate the guide.")
                Text("Times shown in your device time zone. Swipe the timeline to see later programmes.", style = MaterialTheme.typography.bodySmall)
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
        items(visibleChannels, key = { "grid:${it.id}" }) { channel ->
            LiveTvGridChannelRow(channel, uiState, now, windowStart, windowEnd, scrollState)
        }
        if (visibleChannels.isEmpty()) {
            item(key = "guide_empty") {
                Text("No channels match this filter.", modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun LiveTvGridChannelRow(
    channel: LiveTvChannel,
    uiState: LiveTvUiState,
    now: Long,
    windowStart: Long,
    windowEnd: Long,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    val guideKey = channel.guideId ?: channel.name
    val programmes = uiState.programmes[guideKey]
        ?: uiState.programmes.entries.firstOrNull { it.key.equals(guideKey, true) }?.value
        ?: emptyList()
    val totalWidth = halfHourWidth * (guideHours * 2)
    Row(modifier = Modifier.fillMaxWidth().height(80.dp)) {
        Box(
            modifier = Modifier.width(channelWidth).fillMaxHeight().clickable { LiveTvRepository.requestPlayback(channel) }.padding(8.dp),
        ) {
            Text(channel.name, style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
