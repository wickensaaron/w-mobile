package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.watching.domain.DefaultContinueWatchingLimit
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.WatchingProgressRecord
import com.nuvio.app.features.watching.domain.continueWatchingProgressEntries
import com.nuvio.app.features.watching.domain.isProgressComplete
import com.nuvio.app.features.watching.domain.isSeriesLikeWatchingContentType
import com.nuvio.app.features.watching.domain.resumeProgressForSeries
import com.nuvio.app.features.watching.domain.shouldStoreProgress
import com.nuvio.app.features.watching.sync.LegacyUnboundSyncIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val ContinueWatchingLimit = DefaultContinueWatchingLimit
private const val WatchProgressSnapshotConflictToleranceMs = 1_000L

@Serializable
internal data class StoredWatchProgressPayload(
    val entries: List<WatchProgressEntry> = emptyList(),
    val lastSuccessfulPushEpochMs: Long = 0L,
    val deltaCursorEventId: Long = 0L,
    val deltaInitialized: Boolean = false,
    val dirtyProgressKeys: Set<String> = emptySet(),
    val pendingDeletes: List<WatchProgressEntry> = emptyList(),
    val syncIdentity: String? = null,
    val otherIdentities: Map<String, StoredWatchProgressPayload> = emptyMap(),
)

internal fun StoredWatchProgressPayload.forSyncIdentity(identity: String): StoredWatchProgressPayload {
    if (syncIdentity == identity) return this
    if (syncIdentity == null) {
        if (dirtyProgressKeys.isEmpty() && pendingDeletes.isEmpty()) return copy(syncIdentity = identity)
        return StoredWatchProgressPayload(
            syncIdentity = identity,
            otherIdentities = otherIdentities + (
                LegacyUnboundSyncIdentity to copy(otherIdentities = emptyMap())
            ),
        )
    }
    val previous = copy(otherIdentities = emptyMap())
    val selected = otherIdentities[identity] ?: StoredWatchProgressPayload()
    return selected.copy(
        syncIdentity = identity,
        otherIdentities = otherIdentities - identity + (syncIdentity to previous),
    )
}

internal object WatchProgressCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeEntries(payload: String): List<WatchProgressEntry> =
        decodePayload(payload).entries

    fun decodePayload(payload: String): StoredWatchProgressPayload =
        runCatching {
            json.decodeFromString<StoredWatchProgressPayload>(payload).scrubCoreSources().let { storedPayload ->
                val migratedEntries = storedPayload.entries
                    .map { entry -> entry.normalizedCompletion().withResolvedProgressKey() }
                    .newestByProgressKey()
                    .values
                    .sortedWith(watchProgressEntryFreshnessComparator.reversed())
                val migratedKeys = migratedEntries.mapTo(mutableSetOf()) { it.resolvedProgressKey() }
                storedPayload.copy(
                    entries = migratedEntries,
                    dirtyProgressKeys = storedPayload.dirtyProgressKeys.intersect(migratedKeys),
                    pendingDeletes = storedPayload.pendingDeletes
                        .map { it.normalizedCompletion().withResolvedProgressKey() }
                        .newestByProgressKey()
                        .filterKeys { it !in migratedKeys }
                        .values.toList(),
                )
            }
        }.getOrDefault(StoredWatchProgressPayload())

    fun encodeEntries(entries: Collection<WatchProgressEntry>): String =
        encodePayload(
            entries = entries,
            lastSuccessfulPushEpochMs = 0L,
            deltaCursorEventId = 0L,
            deltaInitialized = false,
        )

    fun encodePayload(
        entries: Collection<WatchProgressEntry>,
        lastSuccessfulPushEpochMs: Long,
        deltaCursorEventId: Long,
        deltaInitialized: Boolean,
        dirtyProgressKeys: Set<String> = emptySet(),
        pendingDeletes: Collection<WatchProgressEntry> = emptyList(),
        syncIdentity: String? = null,
        otherIdentities: Map<String, StoredWatchProgressPayload> = emptyMap(),
    ): String =
        json.encodeToString(
            StoredWatchProgressPayload(
                entries = entries.map { it.scrubCoreSource() }
                    .newestByProgressKey()
                    .values
                    .sortedWith(watchProgressEntryFreshnessComparator.reversed()),
                lastSuccessfulPushEpochMs = lastSuccessfulPushEpochMs,
                deltaCursorEventId = deltaCursorEventId,
                deltaInitialized = deltaInitialized,
                dirtyProgressKeys = dirtyProgressKeys,
                pendingDeletes = pendingDeletes.map { it.scrubCoreSource() }.newestByProgressKey().values.toList(),
                syncIdentity = syncIdentity,
                otherIdentities = otherIdentities.mapValues { it.value.scrubCoreSources() },
            ),
        )
}

internal fun shouldStoreWatchProgress(
    positionMs: Long,
    durationMs: Long,
): Boolean = shouldStoreProgress(positionMs = positionMs, durationMs = durationMs)

internal fun isWatchProgressComplete(
    positionMs: Long,
    durationMs: Long,
    isEnded: Boolean,
): Boolean = isProgressComplete(
    positionMs = positionMs,
    durationMs = durationMs,
    isEnded = isEnded,
)

internal fun List<WatchProgressEntry>.resumeEntryForSeries(metaId: String): WatchProgressEntry? {
    val candidates = newestByProgressKey().values.toList()
    val normalizedMetaId = metaId.trim()
    if (normalizedMetaId.isEmpty()) return null
    val contentCandidates = candidates.filter { entry ->
        entry.parentMetaId.trim() == normalizedMetaId
    }
    val seed = contentCandidates.firstOrNull() ?: return null
    val hasSeriesCandidate = contentCandidates.any { entry ->
        entry.parentMetaType.isSeriesLikeWatchingContentType()
    }
    val requestedType = if (hasSeriesCandidate) "series" else seed.parentMetaType

    return resumeProgressForSeries(
        content = WatchingContentRef(type = requestedType, id = normalizedMetaId),
        progressRecords = candidates.map(WatchProgressEntry::toDomainProgressRecord),
    )?.let { record ->
        candidates.firstOrNull { entry -> entry.resolvedProgressKey() == record.identityKey }
    }
}

internal fun List<WatchProgressEntry>.continueWatchingEntries(
    limit: Int = ContinueWatchingLimit,
): List<WatchProgressEntry> {
    val selectionEntries = filter { entry ->
        entry.isEffectivelyCompleted || entry.shouldTreatAsInProgressForContinueWatching()
    }.newestByProgressKey().values.toList()
    val domainEntries = continueWatchingProgressEntries(
        progressRecords = selectionEntries.map(WatchProgressEntry::toDomainProgressRecord),
        limit = limit,
    )
    val identityKeys = domainEntries.map { record -> record.identityKey }.toSet()
    return selectionEntries
        .filter { entry -> entry.resolvedProgressKey() in identityKeys }
        .filter { entry -> entry.shouldTreatAsInProgressForContinueWatching() }
        .sortedByDescending { it.lastUpdatedEpochMs }
}

internal fun WatchProgressEntry.shouldTreatAsInProgressForContinueWatching(): Boolean {
    val entry = normalizedCompletion()
    if (entry.isEffectivelyCompleted) return false

    val hasStartedPlayback = entry.lastPositionMs > 0L ||
        entry.normalizedProgressPercent?.let { it > 0f } == true
    if (!hasStartedPlayback) return false

    return entry.source != WatchProgressSourceTraktHistory &&
        entry.source != WatchProgressSourceTraktShowProgress
}

internal fun WatchProgressEntry.shouldUseAsCompletedSeedForContinueWatching(): Boolean {
    val entry = normalizedCompletion()
    if (isMalformedNextUpSeedContentId(entry.parentMetaId)) return false
    return entry.isEffectivelyCompleted
}

internal fun shouldReplaceProgressSnapshotEntry(
    existing: WatchProgressEntry,
    candidate: WatchProgressEntry,
): Boolean {
    val normalizedExisting = existing.normalizedCompletion()
    val normalizedCandidate = candidate.normalizedCompletion()
    val existingInProgress = normalizedExisting.shouldTreatAsInProgressForContinueWatching()
    val candidateInProgress = normalizedCandidate.shouldTreatAsInProgressForContinueWatching()
    if (existingInProgress != candidateInProgress) {
        val inProgressEntry = if (candidateInProgress) normalizedCandidate else normalizedExisting
        val completedEntry = if (candidateInProgress) normalizedExisting else normalizedCandidate
        val inProgressIsCurrentEnough =
            inProgressEntry.lastUpdatedEpochMs >= completedEntry.lastUpdatedEpochMs - WatchProgressSnapshotConflictToleranceMs
        return if (candidateInProgress) inProgressIsCurrentEnough else !inProgressIsCurrentEnough
    }
    return normalizedCandidate.lastUpdatedEpochMs > normalizedExisting.lastUpdatedEpochMs
}

internal fun shouldCascadeCompletedProgressToWatchedHistory(
    entry: WatchProgressEntry,
    providerOwnsCompletedHistory: Boolean,
): Boolean = !providerOwnsCompletedHistory && entry.normalizedCompletion().isCompleted

internal fun String?.isSeriesTypeForContinueWatching(): Boolean =
    equals("series", ignoreCase = true) || equals("tv", ignoreCase = true)

internal fun isMalformedNextUpSeedContentId(contentId: String?): Boolean {
    val trimmed = contentId?.trim().orEmpty()
    if (trimmed.isEmpty()) return true
    return when (trimmed.lowercase()) {
        "tmdb", "imdb", "trakt", "tmdb:", "imdb:", "trakt:" -> true
        else -> false
    }
}

private fun WatchProgressEntry.toDomainProgressRecord(): WatchingProgressRecord =
    normalizedCompletion().let { entry ->
        WatchingProgressRecord(
            content = WatchingContentRef(
                type = entry.parentMetaType,
                id = entry.parentMetaId,
            ),
            videoId = entry.videoId,
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            lastUpdatedEpochMs = entry.lastUpdatedEpochMs,
            lastPositionMs = entry.lastPositionMs,
            isCompleted = entry.isEffectivelyCompleted,
            episodeTitle = entry.episodeTitle,
            episodeThumbnail = entry.episodeThumbnail,
            identityKey = entry.resolvedProgressKey(),
        )
    }


/** Remove Core and Live TV playback credentials while retaining ordinary addon links. */
internal fun scrubCoreSourceUrl(providerAddonId: String?, url: String?): String? {
    if (providerAddonId?.trim()?.lowercase() in setOf(
            "live", "livetv", "live-tv", "live_tv", "catchup", "catchup-partial",
            "live-tv-replay", "live-tv-catchup",
        )) return null
    if (providerAddonId == "wcore" || providerAddonId?.startsWith("wcore:", ignoreCase = true) == true) return null
    if (url == null) return null
    if (url.startsWith("wcore-source://", ignoreCase = true)) return null
    val path = url.substringBefore('?').substringBefore('#').let {
        if ("://" in it) "/" + it.substringAfter("://").substringAfter('/', "") else it
    }.lowercase()
    return url.takeUnless { path.startsWith("/api/wcore/stream/") || path.startsWith("/api/v1/stream/") ||
        path.startsWith("/api/v1/playback/stream/") }
}

internal fun WatchProgressEntry.scrubCoreSource(): WatchProgressEntry =
    copy(lastSourceUrl = if (isUnsupportedMobileLiveTvProgress()) null else scrubCoreSourceUrl(providerAddonId, lastSourceUrl))

private fun StoredWatchProgressPayload.scrubCoreSources(): StoredWatchProgressPayload = copy(
    entries = entries.map { it.scrubCoreSource() }, pendingDeletes = pendingDeletes.map { it.scrubCoreSource() },
    otherIdentities = otherIdentities.mapValues { it.value.scrubCoreSources() },
)
