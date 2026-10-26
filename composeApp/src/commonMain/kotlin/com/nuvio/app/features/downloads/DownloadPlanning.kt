package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
internal enum class DownloadQuality(val label: String, val hint: String) {
    Small("Small", "720p preferred"), Balanced("Balanced", "1080p preferred"), Best("Best", "Highest available quality")
}

@Serializable
internal data class DownloadPreferences(val quality: DownloadQuality = DownloadQuality.Balanced, val wifiOnly: Boolean = true)

internal fun eligibleDownloadEpisodes(videos: List<MetaVideo>, season: Int, today: String = Clock.System.now().toString().take(10)): List<MetaVideo> =
    videos.filter { it.available && it.season == season && it.episode != null && it.id.isNotBlank() &&
        (it.released?.take(10)?.let { date -> !Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) || date <= today } != false) }
        .distinctBy { it.id }.sortedBy { it.episode }

internal fun nextUnwatchedDownloads(videos: List<MetaVideo>, existingIds: Set<String>, watchedIds: Set<String>): Set<String> =
    videos.filter { it.id !in existingIds && it.id !in watchedIds }.take(3).mapTo(linkedSetOf()) { it.id }

internal fun StreamItem.downloadResolution(): Int {
    val text = listOfNotNull(name, title, description, behaviorHints.filename).joinToString(" ").lowercase()
    return when {
        Regex("\\b(2160p?|4k|uhd)\\b").containsMatchIn(text) -> 2160
        Regex("\\b(1080p?|fhd)\\b").containsMatchIn(text) -> 1080
        Regex("\\b(720p?|hd)\\b").containsMatchIn(text) -> 720
        Regex("\\b(480p?|sd)\\b").containsMatchIn(text) -> 480
        else -> 0
    }
}

internal fun rankedDownloadSources(sources: List<StreamItem>, quality: DownloadQuality): List<StreamItem> {
    val eligible = sources.filter { it.isDirectFileDownloadSource }
    val known = eligible
    val desired = when (quality) { DownloadQuality.Small -> 720; DownloadQuality.Balanced -> 1080; DownloadQuality.Best -> Int.MAX_VALUE }
    return known.sortedWith(compareBy<StreamItem> {
        when { it.downloadResolution() == 0 -> 2; it.downloadResolution() <= desired -> 0; else -> 1 }
    }.thenBy {
        if (it.hasEnglishDownloadHint()) 0 else 1
    }.thenByDescending {
        if (it.downloadResolution() <= desired) it.downloadResolution() else -it.downloadResolution()
    }.thenBy { it.behaviorHints.videoSize?.takeIf { bytes -> bytes > 0 } ?: Long.MAX_VALUE })
}

internal fun downloadSizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${(bytes / (1024.0 * 1024 * 1024) * 10).toLong() / 10.0} GB"
    bytes >= 1024L * 1024 -> "${(bytes / (1024.0 * 1024)).toLong()} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

internal fun downloadTransferDetail(item: DownloadItem): String? {
    if (item.status != DownloadStatus.Downloading || item.isQueued || item.bytesPerSecond <= 0) return null
    val speed = "${downloadSizeLabel(item.bytesPerSecond)}/s"
    val remaining = item.totalBytes?.takeIf { it > item.downloadedBytes }?.let { (it - item.downloadedBytes) / item.bytesPerSecond }
    return speed + (remaining?.let { if (it < 60) " - Less than a minute left" else " - ${it / 60} min left" } ?: "")
}

internal fun chooseDownloadSource(sources: List<StreamItem>, quality: DownloadQuality): StreamItem? =
    rankedDownloadSources(sources, quality).firstOrNull()

internal fun isEnglishDownloadLanguage(language: String?): Boolean {
    val normalized = language?.trim()?.lowercase()?.replace('_', '-') ?: return false
    return normalized == "english" || normalized == "eng" || normalized == "en" || normalized.startsWith("en-")
}

/** Only an actual audio-track probe can make a candidate ready for automatic downloading. */
internal expect suspend fun confirmDownloadEnglishAudio(stream: StreamItem): Boolean

internal suspend fun chooseEnglishDownloadSource(
    sources: List<StreamItem>, quality: DownloadQuality, maxCandidates: Int = 5,
    verify: suspend (StreamItem) -> Boolean,
): StreamItem? {
    for (candidate in rankedDownloadSources(sources, quality).take(maxCandidates.coerceIn(1, 20))) {
        val confirmed = try { verify(candidate) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        if (confirmed) return candidate
    }
    return null
}

internal fun isEnglishDownloadAudio(language: String?, label: String?): Boolean {
    if (isEnglishDownloadLanguage(language)) return true
    if (!language.isNullOrBlank() && language.lowercase() != "und") return false
    return Regex("(?i)\\b(english|eng|en)\\b").containsMatchIn(label.orEmpty())
}

/** Hints only affect search order; container audio tracks must still pass the native check. */
private fun StreamItem.hasEnglishDownloadHint(): Boolean {
    if (clientResolve?.stream?.raw?.parsed?.languages.orEmpty().any(::isEnglishDownloadLanguage)) return true
    val text = listOfNotNull(name, title, description, behaviorHints.filename).joinToString(" ")
    return Regex("(?i)\\b(english|eng)\\b").containsMatchIn(text) ||
        text.contains("\uD83C\uDDEC\uD83C\uDDE7") || text.contains("\uD83C\uDDFA\uD83C\uDDF8")
}
