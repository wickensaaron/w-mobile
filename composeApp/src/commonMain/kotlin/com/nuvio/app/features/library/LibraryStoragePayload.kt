package com.nuvio.app.features.library

import com.nuvio.app.features.library.sync.LibrarySyncKey
import com.nuvio.app.features.watching.sync.LegacyUnboundSyncIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class StoredLibraryPayload(
    val items: List<LibraryItem> = emptyList(),
    val deltaCursorEventId: Long = 0L,
    val deltaInitialized: Boolean = false,
    val pendingUpsertKeys: List<LibrarySyncKey> = emptyList(),
    val pendingDeleteKeys: List<LibrarySyncKey> = emptyList(),
    val syncIdentity: String? = null,
    val otherIdentities: Map<String, StoredLibraryPayload> = emptyMap(),
)

internal fun StoredLibraryPayload.forSyncIdentity(identity: String?): StoredLibraryPayload {
    if (syncIdentity == identity) return this
    val previous = copy(otherIdentities = emptyMap())
    if (identity == null) {
        val unbound = otherIdentities[LegacyUnboundSyncIdentity] ?: StoredLibraryPayload()
        return unbound.copy(
            syncIdentity = null,
            otherIdentities = otherIdentities - LegacyUnboundSyncIdentity + (syncIdentity!! to previous),
        )
    }
    if (syncIdentity == null) {
        val selected = otherIdentities[identity] ?: StoredLibraryPayload()
        val hasPending = pendingUpsertKeys.isNotEmpty() || pendingDeleteKeys.isNotEmpty()
        if (!hasPending && otherIdentities.isEmpty()) return copy(syncIdentity = identity)
        val retained = if (hasPending || items.isNotEmpty()) {
            var key = LegacyUnboundSyncIdentity
            var suffix = 2
            while (key in otherIdentities) key = "$LegacyUnboundSyncIdentity#${suffix++}"
            otherIdentities + (key to previous)
        } else otherIdentities
        return selected.copy(
            syncIdentity = identity,
            otherIdentities = retained - identity,
        )
    }
    val selected = otherIdentities[identity] ?: StoredLibraryPayload()
    return selected.copy(
        syncIdentity = identity,
        otherIdentities = otherIdentities - identity + (syncIdentity to previous),
    )
}

internal object LibraryStoragePayloadCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(payload: String): StoredLibraryPayload =
        runCatching {
            json.decodeFromString<StoredLibraryPayload>(payload)
        }.getOrDefault(StoredLibraryPayload())

    fun encode(snapshot: LibraryLocalSnapshot): String =
        json.encodeToString(
            StoredLibraryPayload(
                items = snapshot.items.sortedByDescending(LibraryItem::savedAtEpochMs),
                deltaCursorEventId = snapshot.deltaCursorEventId,
                deltaInitialized = snapshot.deltaInitialized,
                pendingUpsertKeys = snapshot.pendingUpsertKeys,
                pendingDeleteKeys = snapshot.pendingDeleteKeys,
                syncIdentity = snapshot.token.syncIdentity,
                otherIdentities = snapshot.otherIdentities,
            ),
        )
}
