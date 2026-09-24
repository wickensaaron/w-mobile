package com.nuvio.app.features.library

import com.nuvio.app.features.library.sync.LibrarySyncAdapter
import com.nuvio.app.features.watching.sync.replayPendingWrites

/** Applies saved local intent before a server read, acknowledging each exact mutation separately. */
internal suspend fun replayLibraryPendingWrites(
    state: LibraryLocalState,
    token: LibraryProfileToken,
    adapter: LibrarySyncAdapter,
    isCurrentAccount: () -> Boolean,
    onAcknowledged: (LibraryLocalSnapshot) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    val snapshot = state.snapshot()
    if (snapshot.token != token || !isCurrentAccount()) return
    val isCurrent = { state.isContentCurrent(snapshot) && isCurrentAccount() }
    replayPendingWrites(
        deletes = snapshot.pendingDeleteKeys,
        upserts = emptyList(),
        isCurrent = isCurrent,
        isPendingDelete = { state.isPendingDelete(token, it) },
        isPendingUpsert = { false },
        delete = { adapter.deleteItems(token.profileId, listOf(it)) },
        upsert = {},
        acknowledgeDelete = {
            state.acknowledgeDelete(token, it, snapshot.contentRevision)?.let(onAcknowledged)
        },
        acknowledgeUpsert = {},
        onFailure = onFailure,
    )
    val itemsByKey = snapshot.items.associateBy { libraryItemKey(it.id, it.type) }
    replayPendingWrites(
        deletes = emptyList(),
        upserts = snapshot.pendingUpsertKeys.mapNotNull {
            itemsByKey[libraryItemKey(it.contentId, it.contentType)]
        },
        isCurrent = isCurrent,
        isPendingDelete = { false },
        isPendingUpsert = { state.isPendingUpsert(token, it) },
        delete = {},
        upsert = { adapter.pushItems(token.profileId, listOf(it)) },
        acknowledgeDelete = {},
        acknowledgeUpsert = {
            state.acknowledgeUpsert(token, it, snapshot.contentRevision)?.let(onAcknowledged)
        },
        onFailure = onFailure,
    )
}
