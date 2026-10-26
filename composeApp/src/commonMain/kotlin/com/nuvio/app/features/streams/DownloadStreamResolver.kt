package com.nuvio.app.features.streams

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.servers.ServerStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Independent of the foreground stream picker and autoplay state. Never starts playback/Core acquisition. */
internal suspend fun resolveDownloadStreams(type: String, videoId: String, season: Int?, episode: Int?): List<StreamItem> = coroutineScope {
    val embedded = MetaDetailsRepository.findEmbeddedStreams(videoId)
    if (embedded.isNotEmpty()) return@coroutineScope embedded
    val tasks = mutableListOf<suspend () -> List<StreamItem>>()
    ServerStreams.sources(type, videoId, season, episode).forEach { source -> tasks += suspend { source.load().streams } }
    if (!ServerStreams.isNativeRequest(videoId)) {
        AddonRepository.uiState.value.addons.enabledAddons().forEach { addon ->
            val manifest = addon.manifest ?: return@forEach
            if (manifest.supportsStream(type, videoId)) tasks += suspend {
                StreamParser.parse(fetchAddonResponseText(buildAddonResourceUrl(manifest.transportUrl, "stream", type, videoId), false),
                    addon.displayTitle.ifBlank { manifest.name }, addon.streamAddonInstanceId(manifest.id), manifest.logoUrl)
            }
        }
        if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.initialize()
            PluginRepository.getEnabledScrapersForType(type).forEach { scraper -> tasks += suspend {
                PluginRepository.executeScraper(scraper, pluginContentId(videoId, season, episode), type, season, episode)
                    .getOrThrow().map { it.toStreamItem(scraper) }
            } }
        }
    }
    val permits = Semaphore(4)
    tasks.map { task -> async {
        permits.withPermit {
            try { withTimeoutOrNull(25_000) { task() }.orEmpty() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        }
    } }.awaitAll().flatten().distinctBy { it.playableDirectUrl ?: "${it.addonId}:${it.infoHash}:${it.fileIdx}" }
}
