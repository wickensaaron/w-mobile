package com.nuvio.app.features.watching.sync

import kotlinx.coroutines.CancellationException

/** Replays durable local intent before reading the remote snapshot or delta. */
internal suspend fun <T> replayPendingWrites(
    deletes: Collection<T>,
    upserts: Collection<T>,
    isCurrent: () -> Boolean,
    isPendingDelete: (T) -> Boolean,
    isPendingUpsert: (T) -> Boolean,
    delete: suspend (T) -> Unit,
    upsert: suspend (T) -> Unit,
    acknowledgeDelete: (T) -> Unit,
    acknowledgeUpsert: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    suspend fun attempt(item: T, operation: suspend (T) -> Unit, acknowledge: (T) -> Unit) {
        try {
            operation(item)
            if (isCurrent()) acknowledge(item)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error)
        }
    }

    for (item in deletes) {
        if (!isCurrent()) return
        if (isPendingDelete(item)) attempt(item, delete, acknowledgeDelete)
    }
    for (item in upserts) {
        if (!isCurrent()) return
        if (isPendingUpsert(item)) attempt(item, upsert, acknowledgeUpsert)
    }
}
