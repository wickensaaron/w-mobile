package com.nuvio.app.features.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import coil3.compose.AsyncImage
import com.nuvio.app.core.format.formatLocalHourMinute
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.LiveTvAccountGuideRow
import com.nuvio.app.features.livetv.LiveTvProgramme
import com.nuvio.app.features.livetv.LiveTvRepository
import com.nuvio.app.features.livetv.LiveTvUiState
import com.nuvio.app.features.livetv.filterLiveTvAccountGuideRows
import com.nuvio.app.features.livetv.isLiveTvGuideRowFavourite
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.w_brand_mark
import org.jetbrains.compose.resources.painterResource
import kotlin.time.Clock

private const val halfHourMs = 30L * 60 * 1000
private const val hourMs = 60L * 60 * 1000
private val channelWidth = 116.dp
private val halfHourWidth = 132.dp
private val phoneCardShape = RoundedCornerShape(16.dp)
private val sportGroupName = Regex("(?:^|[^a-z])(?:sports?|football|cricket|golf|racing)(?:$|[^a-z])")

/** The phone keeps the usable 'now' view separate from its detailed, horizontally swipeable EPG. */
@Composable
internal fun LiveTvGuideGrid(
    uiState: LiveTvUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    favoritesOnly: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onChannelsClick: () -> Unit,
) {
    var nowMs by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMs = Clock.System.now().toEpochMilliseconds()
        }
    }
    val localProfileId = ProfileRepository.activeProfileId
    val accountOwner = uiState.accountGuideOwner
    var guideTab by rememberSaveable(localProfileId, accountOwner) { mutableStateOf(false) }
    var sportOnly by rememberSaveable(localProfileId, accountOwner) { mutableStateOf(false) }
    var offsetSlots by rememberSaveable(localProfileId, accountOwner) { mutableStateOf(0) }
    var selectedChannelId by remember(localProfileId, accountOwner) { mutableStateOf<String?>(null) }
    var selectedStartMs by remember(localProfileId, accountOwner) { mutableStateOf<Long?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val prepared = rememberPreparedLiveTvGuide(uiState, nowMs, favoritesOnly)
    val visibleRows = remember(prepared.presentation.rows, prepared.programmesByChannelId, searchQuery, sportOnly, nowMs) {
        filterLiveTvAccountGuideRows(prepared.presentation.rows, searchQuery).filter { row ->
            (!sportOnly || row.isSportChannel()) &&
                prepared.programmesByChannelId[row.channel.id].orEmpty().any {
                    it.stopEpochMs > nowMs && it.startEpochMs < nowMs + 24 * hourMs
                }
        }
    }
    val selected = remember(visibleRows, selectedChannelId, selectedStartMs, prepared.programmesByChannelId) {
        visibleRows.firstOrNull { it.channel.id == selectedChannelId }?.let { row ->
            prepared.programmesByChannelId[row.channel.id].orEmpty().firstOrNull { it.startEpochMs == selectedStartMs }?.let { row to it }
        }
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(offsetSlots, guideTab) { scrollState.scrollTo(0) }
    val windowStart = nowMs / halfHourMs * halfHourMs + offsetSlots * halfHourMs
    val windowEnd = windowStart + 3 * hourMs

    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .imePadding(),
        contentPadding = PaddingValues(top = 16.dp, bottom = nuvioSafeBottomPadding(24.dp)),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "live_header") {
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource(Res.drawable.w_brand_mark), null, Modifier.size(38.dp), contentScale = ContentScale.Fit)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("W MEDIA PLAYER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("Live TV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onChannelsClick, modifier = Modifier.semantics { contentDescription = "Live TV sources and guide settings" }) {
                        Text("Settings")
                    }
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search UK channels") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    trailingIcon = if (searchQuery.isBlank()) null else {
                        { TextButton(onClick = { onSearchQueryChange("") }) { Text("Clear") } }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoneModeButton("Now", !guideTab, Modifier.weight(1f)) { guideTab = false }
                    PhoneModeButton("Guide", guideTab, Modifier.weight(1f)) { guideTab = true }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoneFilterButton(if (uiState.accountGuideSnapshot?.preferences?.ukOnly == false) "All channels" else "All UK",
                        !favoritesOnly && !sportOnly) {
                        sportOnly = false; onFavoritesOnlyChange(false)
                    }
                    PhoneFilterButton("Favourites", favoritesOnly) {
                        sportOnly = false; onFavoritesOnlyChange(true)
                    }
                    PhoneFilterButton("Sport", sportOnly) {
                        sportOnly = true; onFavoritesOnlyChange(false)
                    }
                }
            }
        }
        item(key = "live_status") {
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (guideTab) "Programme guide" else "On now", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text("${visibleRows.size} channels", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (uiState.isLoading || uiState.isRestoringAccountSources) Text("Restoring your channels…", style = MaterialTheme.typography.bodySmall)
                if (uiState.isGuideLoading || prepared.isPreparing) Text("Loading programme data…", style = MaterialTheme.typography.bodySmall)
                if (uiState.isAccountGuideSaving || uiState.isAccountGuideSyncing) Text("Saving your guide choices…", style = MaterialTheme.typography.bodySmall)
                uiState.accountGuideSyncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                prepared.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                uiState.errorMessage?.let { Text("Some channels could not load. Check Sources in settings.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                uiState.guideErrorMessage?.let { Text("Some guide sources could not load. Retry or check Sources in settings.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (uiState.guideErrorMessage != null || prepared.message != null) {
                    TextButton(onClick = LiveTvRepository::refreshGuide, enabled = uiState.hasGuideSources) { Text("Retry guide") }
                }
            }
        }
        if (guideTab) {
            item(key = "guide_date") {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { offsetSlots = (offsetSlots - 1).coerceAtLeast(0) }, enabled = offsetSlots > 0,
                        modifier = Modifier.semantics { contentDescription = "Earlier guide time" }) { Text("‹") }
                    Text(if (offsetSlots == 0) "Now" else formatLocalHourMinute(windowStart), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { offsetSlots = (offsetSlots + 1).coerceAtMost(48) }, enabled = offsetSlots < 48,
                        modifier = Modifier.semantics { contentDescription = "Later guide time" }) { Text("Later  ›") }
                }
            }
            stickyHeader(key = "guide_axis") {
                Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 10.dp)) {
                    Text("CHANNEL", modifier = Modifier.width(channelWidth).padding(start = 16.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(modifier = Modifier.weight(1f).horizontalScroll(scrollState)) {
                        repeat(6) { slot ->
                            Text(formatLocalHourMinute(windowStart + slot * halfHourMs), modifier = Modifier.width(halfHourWidth),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (selected != null) {
                item(key = "selected_programme") {
                    SelectedProgrammeCard(selected.first, selected.second, nowMs, Modifier.padding(horizontal = 16.dp))
                }
            }
            items(visibleRows, key = { "guide:${it.channel.id}" }) { row ->
                PhoneGuideRow(row, prepared.programmesByChannelId[row.channel.id].orEmpty(),
                    uiState, nowMs, windowStart, windowEnd, scrollState) { programme ->
                    selectedChannelId = row.channel.id; selectedStartMs = programme.startEpochMs
                }
            }
        } else {
            items(visibleRows, key = { "now:${it.channel.id}" }) { row ->
                PhoneNowCard(row, prepared.programmesByChannelId[row.channel.id].orEmpty(), uiState, nowMs,
                    Modifier.padding(horizontal = 16.dp))
            }
        }
        if (visibleRows.isEmpty() && !prepared.isPreparing && !uiState.isGuideLoading && !uiState.isLoading && !uiState.isRestoringAccountSources) {
            item(key = "live_empty") {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (favoritesOnly) "No favourites with programme data" else "No matching programmes",
                        style = MaterialTheme.typography.titleMedium)
                    Text("Channels without EPG are hidden. Change the search or guide choices in Settings.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onChannelsClick) { Text("Sources & settings") }
                }
            }
        }
    }
}

private fun LiveTvAccountGuideRow.isSportChannel(): Boolean {
    return channelNumber?.let { it in 401..499 } == true ||
        sportGroupName.containsMatchIn(channel.group.orEmpty().lowercase())
}

@Composable
private fun PhoneModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = modifier.height(44.dp).clip(shape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PhoneFilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(modifier = Modifier.height(40.dp).clip(shape)
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
        .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable(onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PhoneChannelMark(row: LiveTvAccountGuideRow) {
    val logo = row.channel.logoUrl?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
    Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        if (logo != null) AsyncImage(model = logo, contentDescription = null, modifier = Modifier.size(38.dp), contentScale = ContentScale.Fit)
        else Text(row.displayName.firstOrNull()?.uppercase() ?: "TV", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PhoneFavourite(row: LiveTvAccountGuideRow, uiState: LiveTvUiState, nowMs: Long) {
    val favourite = if (row.channel.accountScope == null) row.channel.id in uiState.favoriteChannelIds
        else isLiveTvGuideRowFavourite(row, uiState.favoriteChannelIds, uiState.programmes, nowMs)
    val enabled = row.channel.accountScope == null ||
        (!uiState.isAccountGuideSaving && !uiState.isAccountGuideSyncing && uiState.accountGuideSnapshot != null)
    Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
        .clickable(enabled = enabled) { LiveTvRepository.toggleFavoriteGuideRow(row) }
        .semantics {
            contentDescription = if (favourite) "Remove ${row.displayName} from favourites" else "Add ${row.displayName} to favourites"
        },
        contentAlignment = Alignment.Center) {
        Text(if (favourite) "★" else "☆", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PhoneNowCard(row: LiveTvAccountGuideRow, programmes: List<LiveTvProgramme>,
    uiState: LiveTvUiState, nowMs: Long, modifier: Modifier = Modifier) {
    val current = programmes.firstOrNull { it.startEpochMs <= nowMs && it.stopEpochMs > nowMs }
    val next = programmes.firstOrNull { it.startEpochMs > nowMs }
    val fraction = current?.let {
        ((nowMs - it.startEpochMs).toFloat() / (it.stopEpochMs - it.startEpochMs).coerceAtLeast(1)).coerceIn(0f, 1f)
    } ?: 0f
    Column(modifier = modifier.fillMaxWidth().clip(phoneCardShape).background(MaterialTheme.colorScheme.surface)
        .clickable { LiveTvRepository.requestPlayback(row.channel) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhoneChannelMark(row)
            Column(modifier = Modifier.weight(1f)) {
                Text(row.channelNumber?.let { "$it · ${row.displayName}" } ?: row.displayName,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current?.title ?: next?.let { "Guide starts ${formatLocalHourMinute(it.startEpochMs)} · ${it.title}" }
                    ?: "Programme information pending", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            PhoneFavourite(row, uiState, nowMs)
        }
        if (current != null) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
            Text("Live · ${formatLocalHourMinute(current.startEpochMs)} – ${formatLocalHourMinute(current.stopEpochMs)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (current != null && next != null) Text("Next  ${formatLocalHourMinute(next.startEpochMs)} · ${next.title}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SelectedProgrammeCard(row: LiveTvAccountGuideRow, programme: LiveTvProgramme, nowMs: Long, modifier: Modifier) {
    val current = programme.startEpochMs <= nowMs && programme.stopEpochMs > nowMs
    Column(modifier = modifier.fillMaxWidth().clip(phoneCardShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SELECTED PROGRAMME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(programme.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("${row.displayName} · ${formatLocalHourMinute(programme.startEpochMs)} – ${formatLocalHourMinute(programme.stopEpochMs)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        programme.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
        Button(onClick = { LiveTvRepository.requestPlayback(row.channel) }) { Text(if (current) "▶  Watch live" else "▶  Watch channel") }
    }
}

@Composable
private fun PhoneGuideRow(row: LiveTvAccountGuideRow, programmes: List<LiveTvProgramme>, uiState: LiveTvUiState,
    nowMs: Long, windowStart: Long, windowEnd: Long, scrollState: androidx.compose.foundation.ScrollState,
    onSelect: (LiveTvProgramme) -> Unit) {
    val totalWidth = halfHourWidth * 6
    Row(modifier = Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.width(channelWidth).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.channelNumber?.let { "$it" } ?: "TV", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                PhoneFavourite(row, uiState, nowMs)
            }
            Box(modifier = Modifier.fillMaxWidth().height(40.dp)
                .clickable { LiveTvRepository.requestPlayback(row.channel) }, contentAlignment = Alignment.CenterStart) {
                Text(row.displayName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(scrollState)) {
            Box(modifier = Modifier.width(totalWidth).fillMaxHeight()) {
                Row(modifier = Modifier.fillMaxSize()) {
                    repeat(6) {
                        Box(Modifier.width(halfHourWidth).fillMaxHeight().border(0.5.dp, MaterialTheme.colorScheme.outlineVariant))
                    }
                }
                var occupiedUntil = windowStart
                programmes.filter { it.startEpochMs < windowEnd && it.stopEpochMs > windowStart }
                    .sortedBy { it.startEpochMs }.forEach { programme ->
                    val start = maxOf(programme.startEpochMs, windowStart, occupiedUntil)
                    val end = minOf(programme.stopEpochMs, windowEnd)
                    if (end <= start) return@forEach
                    occupiedUntil = end
                    val x = ((start - windowStart).toFloat() / halfHourMs * halfHourWidth.value).dp
                    val width = (((end - start).toFloat() / halfHourMs * halfHourWidth.value) - 3f).coerceAtLeast(1f).dp
                    val live = programme.startEpochMs <= nowMs && programme.stopEpochMs > nowMs
                    Column(modifier = Modifier.offset(x, 4.dp).width(width).height(80.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onSelect(programme) }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(programme.title, style = MaterialTheme.typography.labelMedium,
                            color = if (live) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (live) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(formatLocalHourMinute(programme.startEpochMs), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
