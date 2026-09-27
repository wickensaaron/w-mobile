package com.nuvio.app.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal data class RecoveredAccountPayload<T>(val value: T?, val damagedPayload: String? = null)

/** Salvages the local snapshot when just its replacement queue is damaged; keeps the original. */
internal inline fun <reified T> decodeRecoverableAccountPayload(
    json: Json,
    raw: String,
    replacementField: String,
): RecoveredAccountPayload<T> {
    runCatching { json.decodeFromString<T>(raw) }.getOrNull()?.let {
        return RecoveredAccountPayload(it)
    }
    val snapshot = runCatching {
        val fields = json.parseToJsonElement(raw).jsonObject.toMutableMap()
        fields.remove(replacementField)
        json.decodeFromString<T>(JsonObject(fields).toString())
    }.getOrNull()
    return RecoveredAccountPayload(snapshot, raw)
}

/** A complete replacement list, owned by the backend/account that made the edit. */
@Serializable
internal data class AccountReplacement<T>(val owner: String?, val items: List<T>)

/** Empty snapshots after a known remote list can be intentional removals. */
internal fun canSeedEmptyAccountList(
    storedOwner: String?,
    currentOwner: String,
    hasRemoteSnapshot: Boolean,
    starterPending: Boolean,
): Boolean = !hasRemoteSnapshot && (storedOwner == currentOwner || (storedOwner == null && starterPending))

/** Returns false when a pull must wait for durable local intent to be uploaded. */
internal suspend fun <T> replayAccountReplacement(
    pending: AccountReplacement<T>?,
    currentOwner: () -> String?,
    isCurrent: () -> Boolean,
    push: suspend (List<T>) -> Unit,
    acknowledge: (AccountReplacement<T>) -> Unit,
    onFailure: (Throwable) -> Unit,
): Boolean {
    if (pending == null) return isCurrent()
    if (pending.owner == null || pending.owner != currentOwner() || !isCurrent()) return false
    return try {
        push(pending.items)
        if (pending.owner != currentOwner() || !isCurrent()) false else {
            acknowledge(pending)
            true
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onFailure(error)
        false
    }
}
