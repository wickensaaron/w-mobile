package com.nuvio.app.features.livetv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounded provider work; the caller publishes each completion without waiting for a slow neighbour. */
internal suspend fun <T> loadLiveTvSources(
    tasks: List<suspend () -> T>,
    parallelism: Int = 3,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    onResult: suspend (Int, Result<T>) -> Unit,
) = coroutineScope {
    require(parallelism in 1..4)
    val permits = Semaphore(parallelism)
    val completed = Channel<Pair<Int, Result<T>>>(Channel.BUFFERED)
    try {
        tasks.forEachIndexed { index, task ->
            launch(dispatcher) {
                permits.withPermit {
                    val result = try { Result.success(task()) }
                    catch (cancelled: CancellationException) { Result.failure(cancelled) }
                    catch (error: Exception) { Result.failure(error) }
                    completed.send(index to result)
                }
            }
        }
        repeat(tasks.size) {
            val (index, result) = completed.receive()
            currentCoroutineContext().ensureActive()
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            onResult(index, result)
        }
    } finally {
        completed.cancel()
    }
}

/** Source priority, identities and the global device limit are independent of completion order. */
internal suspend fun mergeLiveTvChannelLoads(
    loaded: List<List<LiveTvChannel>?>,
    generation: Int,
): List<LiveTvChannel> {
    val seen = HashSet<String>()
    val merged = ArrayList<LiveTvChannel>()
    var visited = 0
    for (channels in loaded) {
        for (channel in channels.orEmpty()) {
            if (visited++ % 256 == 0) currentCoroutineContext().ensureActive()
            if (seen.add(if (channel.accountScope != null) channel.id else "local:${channel.streamUrl}")) {
                merged += channel.copy(sourceLoadGeneration = generation)
                if (merged.size == MobileLiveTvCatalogueLimit) return merged.toList()
            }
        }
    }
    return merged.toList()
}

/** Process-only, account-bound cache. No provider URLs, stream credentials or programmes go to disk. */
internal class LiveTvGuideMemoryCache {
    private data class Entry(val source: ImportedLiveTvSource, val keys: List<Pair<String, String>>,
        val budget: Int, val loadedAt: Long, val guide: ImportedXmlTvResult)
    private var owner: LiveTvAccountScope? = null
    private val entries = mutableMapOf<String, Entry>()

    fun clear() { entries.clear(); owner = null }

    private fun bind(account: LiveTvAccountScope) {
        if (owner != account) { clear(); owner = account }
    }

    fun get(account: LiveTvAccountScope, source: ImportedLiveTvSource, channels: List<LiveTvChannel>,
        budget: Int, now: Long): ImportedXmlTvResult? {
        bind(account)
        val entry = entries[source.id] ?: return null
        return entry.guide.takeIf { entry.source == source && entry.budget == budget &&
            now >= entry.loadedAt && now - entry.loadedAt < 15 * 60_000 &&
            entry.keys == channels.map { it.id to (it.guideId ?: it.name) } }
    }

    fun put(account: LiveTvAccountScope, source: ImportedLiveTvSource, channels: List<LiveTvChannel>,
        budget: Int, now: Long, guide: ImportedXmlTvResult) {
        bind(account)
        entries[source.id] = Entry(source, channels.map { it.id to (it.guideId ?: it.name) }, budget, now, guide)
    }
}
