package com.nuvio.app.features.party

import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.realtime.Presence
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PartyChannel(
    private val partyId: String,
    private val memberId: String,
) {
    private var channel: RealtimeChannel? = null
    private var jobs: List<Job> = emptyList()
    private val sendMutex = Mutex()
    private var receive: ((String, JsonObject) -> Unit)? = null

    val isConnected: Boolean
        get() = channel?.status?.value == RealtimeChannel.Status.SUBSCRIBED

    suspend fun open(
        scope: CoroutineScope,
        onMessage: (String, JsonObject) -> Unit,
        onPresence: (joins: Map<String, Presence>, leaves: Map<String, Presence>) -> Unit,
        onConnected: (Boolean) -> Unit,
    ) {
        val created = SupabaseProvider.client.channel("watch-party:$partyId:$memberId") {
            isPrivate = true
            broadcast {
                receiveOwnBroadcasts = false
                acknowledgeBroadcasts = false
            }
            presence {
                key = memberId
            }
        }
        channel = created
        receive = onMessage
        jobs = EVENTS.map { event ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                created.broadcastFlow<JsonObject>(event).collect { payload -> onMessage(event, payload) }
            }
        } + scope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.presenceChangeFlow().collect { action -> onPresence(action.joins, action.leaves) }
        } + scope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.status.collect { status -> onConnected(status == RealtimeChannel.Status.SUBSCRIBED) }
        }
        yield()
        created.subscribe(blockUntilSubscribed = false)
    }

    suspend fun send(event: String, payload: JsonObject) {
        if (event !in setOf("state", "status", "state_request")) return
        if (!isConnected) return
        sendMutex.withLock {
            val snapshot = PartyApi.event(partyId, memberId, event, payload)
            snapshot.party?.playback?.let { playback ->
                receive?.invoke("state", PartyApi.json.encodeToJsonElement(PartyPlayback.serializer(), playback).jsonObject)
            }
        }
    }

    suspend fun track(payload: JsonObject) {
        send("status", payload)
    }

    suspend fun close() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        val current = channel ?: return
        channel = null
        receive = null
        runCatching { SupabaseProvider.client.realtime.removeChannel(current) }
    }

    private companion object {
        val EVENTS = listOf("state", "state_request", "status", "control", "member_joined", "member_left", "ended", "media", "stream", "chat")
    }
}
