package com.nuvio.app.features.watchprogress

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Read after the refresh: the device's last player ID is not account activity. */
internal suspend fun loadLatestResumePromptEntry(
    refreshProgress: suspend () -> Unit,
    isCurrentContext: () -> Boolean,
    readProgress: () -> WatchProgressUiState,
): WatchProgressEntry? {
    try {
        // Opening Home must still work offline or when the sync service is slow.
        withTimeoutOrNull(8_000L) { refreshProgress() }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Use the last account snapshot available on this device while offline.
    }
    currentCoroutineContext().ensureActive()
    if (!isCurrentContext()) return null
    val progress = readProgress()
    return progress.entries
        .filter { it.parentMetaId !in progress.hiddenContentIds }
        .maxWithOrNull(watchProgressEntryFreshnessComparator)
}
