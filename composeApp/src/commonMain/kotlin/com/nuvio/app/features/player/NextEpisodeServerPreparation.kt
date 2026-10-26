package com.nuvio.app.features.player

import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException

internal suspend fun prepareNextEpisodeServerStream(
    candidate: StreamItem?,
    prepare: suspend (StreamItem) -> StreamItem = { ServerPlayback.prepare(it) },
): StreamItem? = try {
    if (candidate?.needsServerPreparation == true) prepare(candidate) else candidate
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { null }
