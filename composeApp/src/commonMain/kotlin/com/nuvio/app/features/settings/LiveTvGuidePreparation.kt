package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.features.livetv.LiveTvAccountGuidePresentation
import com.nuvio.app.features.livetv.LiveTvAccountGuideRow
import com.nuvio.app.features.livetv.LiveTvAccountGuideSnapshot
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.livetv.LiveTvManualGuideIndex
import com.nuvio.app.features.livetv.LiveTvProgramme
import com.nuvio.app.features.livetv.LiveTvUiState
import com.nuvio.app.features.livetv.projectLiveTvAccountGuide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal data class PreparedLiveTvGuide(
    val presentation: LiveTvAccountGuidePresentation = LiveTvAccountGuidePresentation(),
    val programmesByChannelId: Map<String, List<LiveTvProgramme>> = emptyMap(),
    val isPreparing: Boolean = false,
    val message: String? = null,
)

private class GuidePreparationInput(val state: LiveTvUiState, val nowMs: Long, val favouritesOnly: Boolean)
private data class GuidePreparationResult(val input: GuidePreparationInput, val guide: PreparedLiveTvGuide)

/** Expensive cleanup is cancelled when its source/account/guide changes; search runs on its result. */
@Composable
internal fun rememberPreparedLiveTvGuide(state: LiveTvUiState, nowMs: Long, favouritesOnly: Boolean): PreparedLiveTvGuide {
    val input = remember(state.channels, state.programmes, state.accountGuideSnapshot, state.accountGuideOwner,
        state.accountSourceGeneration, state.accountSources, state.favoriteChannelIds, state.isAccountGuideSyncing,
        state.isRestoringAccountSources, nowMs / 60_000, favouritesOnly) {
        GuidePreparationInput(state, nowMs, favouritesOnly)
    }
    var result by remember { mutableStateOf<GuidePreparationResult?>(null) }
    LaunchedEffect(input) {
        val guide = try {
            withContext(Dispatchers.Default) { prepareLiveTvGuide(input.state, input.nowMs, input.favouritesOnly) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PreparedLiveTvGuide(message = "The guide could not be organised. Refresh your sources and guide.")
        }
        currentCoroutineContext().ensureActive()
        result = GuidePreparationResult(input, guide)
    }
    // A previous account's prepared rows must never remain visible during a new preparation.
    return result?.takeIf { it.input === input }?.guide ?: PreparedLiveTvGuide(isPreparing = true)
}

internal suspend fun prepareLiveTvGuide(state: LiveTvUiState, nowMs: Long, favouritesOnly: Boolean): PreparedLiveTvGuide {
    val owner = state.accountGuideOwner
    val importedIds = state.channels.asSequence().filter { it.accountScope != null }.map { it.id }.toSet()
    val manualIndex = LiveTvManualGuideIndex(state.programmes, importedIds)
    val snapshot = state.accountGuideSnapshot
    val imported = if (owner == null || snapshot == null) LiveTvAccountGuidePresentation() else projectLiveTvAccountGuide(
        state.channels, state.programmes, snapshot,
        state.accountSources.filter { it.enabled }.map { it.id }, owner, state.accountSourceGeneration, nowMs, favouritesOnly,
    )
    val rows = ArrayList<LiveTvAccountGuideRow>(imported.rows.size)
    rows.addAll(imported.rows)
    val seenIds = rows.mapTo(mutableSetOf()) { it.channel.id }
    var manualWithoutGuide = 0
    // Legacy manual sources remain separate from the account organiser's canonical identities.
    for ((index, channel) in state.channels.withIndex()) {
        if (index % 256 == 0) { currentCoroutineContext().ensureActive(); yield() }
        if (channel.accountScope != null) continue
        if (!seenIds.add(channel.id)) continue
        if (!manualIndex.hasUnexpiredGuide(channel, nowMs)) { manualWithoutGuide++; continue }
        if (favouritesOnly && channel.id !in state.favoriteChannelIds) continue
        rows += LiveTvAccountGuideRow(channel, channel.name, channel.group ?: "Local sources", null, null)
    }
    val programmes = linkedMapOf<String, List<LiveTvProgramme>>()
    for ((index, row) in rows.withIndex()) {
        if (index % 256 == 0) { currentCoroutineContext().ensureActive(); yield() }
        programmes[row.channel.id] = if (row.channel.accountScope == null) manualIndex.programmesFor(row.channel)
            else state.programmes[row.channel.id].orEmpty()
    }
    currentCoroutineContext().ensureActive()
    return PreparedLiveTvGuide(imported.copy(rows = rows.toList(),
        channelsWithoutGuide = imported.channelsWithoutGuide + manualWithoutGuide), programmes.toMap(),
        isPreparing = owner != null && snapshot == null && (state.isAccountGuideSyncing || state.isRestoringAccountSources))
}
