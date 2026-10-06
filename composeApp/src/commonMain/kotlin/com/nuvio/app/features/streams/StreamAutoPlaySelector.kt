package com.nuvio.app.features.streams

import com.nuvio.app.core.build.AppFeaturePolicy

object StreamAutoPlaySelector {

    fun orderAddonStreams(
        groups: List<AddonStreamGroup>,
        installedOrder: List<String>,
    ): List<AddonStreamGroup> {
        if (groups.isEmpty()) return groups

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        val (directDebridEntries, remainingEntries) = groups.partition { group ->
            group.addonId.startsWith("debrid:") ||
                group.streams.any { stream -> stream.isAddonDebridCandidate && stream.isDirectDebridStream }
        }
        val (wCoreEntries, localEntries) = remainingEntries.partition { it.addonId == W_CORE_ADDON_ID }
        if (installedOrder.isEmpty()) return wCoreEntries + directDebridEntries + localEntries

        val (addonEntries, pluginEntries) = localEntries.partition { group ->
            group.addonName in addonRankByName
        }
        val orderedAddons = addonEntries.sortedBy { group ->
            addonRankByName.getValue(group.addonName)
        }
        return wCoreEntries + directDebridEntries + orderedAddons + pluginEntries
    }

    fun selectAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
        preferredAudioLanguage: String? = null,
        balancedSelection: Boolean = balancedAutoPlayEnabled,
    ): StreamItem? =
        evaluateAutoPlayStream(
            streams = streams,
            mode = mode,
            regexPattern = regexPattern,
            source = source,
            installedAddonNames = installedAddonNames,
            selectedAddons = selectedAddons,
            selectedPlugins = selectedPlugins,
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = preferBingeGroupInSelection,
            bingeGroupOnly = bingeGroupOnly,
            debridEnabled = debridEnabled,
            activeResolverProviderId = activeResolverProviderId,
            preferredAudioLanguage = preferredAudioLanguage,
            balancedSelection = balancedSelection,
        ).stream

    fun evaluateAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
        preferredAudioLanguage: String? = null,
        balancedSelection: Boolean = balancedAutoPlayEnabled,
    ): StreamAutoPlayEvaluation {
        if (streams.isEmpty()) return StreamAutoPlayEvaluation()

        val sourceScopedStreams = when (source) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter {
                it.addonName !in installedAddonNames && !it.addonId.startsWith("$W_CORE_ADDON_ID:")
            }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            if (balancedSelection && !BalancedAutoPlayPolicy.isEligible(stream)) return@filter false
            val isAddonStream = stream.addonName in installedAddonNames
            if (stream.addonId.startsWith("$W_CORE_ADDON_ID:")) {
                true
            } else if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return StreamAutoPlayEvaluation()
        if (mode == StreamAutoPlayMode.MANUAL && !bingeGroupOnly) {
            return StreamAutoPlayEvaluation()
        }

        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        val bingeGroupCandidates = if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            candidateStreams.filter { stream -> stream.behaviorHints.bingeGroup == targetBingeGroup }
        } else {
            emptyList()
        }
        val readyBingeGroupCandidates = bingeGroupCandidates.filter { stream ->
            stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
        }
        val preferredReadyStream = if (mode == StreamAutoPlayMode.SMART)
            smartOrder(readyBingeGroupCandidates, preferredAudioLanguage, balancedSelection).firstOrNull()
        else readyBingeGroupCandidates.firstOrNull()
        if (bingeGroupOnly) {
            val readyStreams = if (balancedSelection) {
                if (mode == StreamAutoPlayMode.SMART) smartOrder(readyBingeGroupCandidates, preferredAudioLanguage, true)
                else readyBingeGroupCandidates
            } else preferredReadyStream?.let(::listOf).orEmpty()
            return StreamAutoPlayEvaluation(
                stream = preferredReadyStream,
                readyStreams = readyStreams,
                hasPendingDebridCandidate = preferredReadyStream == null &&
                    bingeGroupCandidates.any {
                        it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
                    },
            )
        }
        if (mode == StreamAutoPlayMode.MANUAL) {
            return StreamAutoPlayEvaluation()
        }
        val preferredStream = if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            val ready = candidateStreams.filter { stream ->
                stream.behaviorHints.bingeGroup == targetBingeGroup &&
                    stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
            }
            if (mode == StreamAutoPlayMode.SMART) smartOrder(ready, preferredAudioLanguage, balancedSelection).firstOrNull()
            else ready.firstOrNull()
        } else {
            null
        }
        val matchingStreams = when (mode) {
            StreamAutoPlayMode.MANUAL -> emptyList()
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams
            StreamAutoPlayMode.SMART -> candidateStreams
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()

                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                    ?: return StreamAutoPlayEvaluation()

                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex(
                        "\\b(${exclusionWords.joinToString("|") { Regex.escape(it) }})\\b",
                        RegexOption.IGNORE_CASE,
                    )
                } else null

                candidateStreams.filter { stream ->
                    val url = stream.playableDirectUrl.orEmpty()

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.streamLabel).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(url)
                    }

                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }
            }
        }
        if (matchingStreams.isEmpty() && preferredStream == null) return StreamAutoPlayEvaluation()

        val readyStreams = buildList {
            preferredStream?.let(::add)
            matchingStreams
                .filter { it.isAutoPlayable(debridEnabled, activeResolverProviderId) }
                .filterNot { it == preferredStream }
                .let { ready -> if (mode == StreamAutoPlayMode.SMART) smartOrder(ready, preferredAudioLanguage, balancedSelection) else ready }
                .forEach(::add)
        }
        val selected = readyStreams.firstOrNull()
        if (selected != null) {
            return StreamAutoPlayEvaluation(
                stream = selected,
                readyStreams = readyStreams,
            )
        }

        return StreamAutoPlayEvaluation(
            readyStreams = readyStreams,
            hasPendingDebridCandidate = matchingStreams.any {
                it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            },
        )
    }

    private fun StreamItem.isAutoPlayable(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean =
        playableDirectUrl != null ||
            (
                AppFeaturePolicy.p2pEnabled &&
                    needsLocalDebridResolve &&
                    p2pInfoHash != null &&
                    !isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            ) ||
            (debridEnabled && isAddonDebridCandidate && isReadyDebridAutoPlay(activeResolverProviderId))

    private fun StreamItem.isReadyDebridAutoPlay(activeResolverProviderId: String?): Boolean =
        when {
            isDirectDebridStream -> clientResolve?.service.matchesResolver(activeResolverProviderId)
            isCachedDebridTorrentStream -> debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)
            else -> false
        }

    private fun StreamItem.isPendingDebridAutoPlay(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean {
        if (!debridEnabled || !isInstalledAddonStream || !needsLocalDebridResolve) return false
        if (!debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)) return false
        val state = debridCacheStatus?.state
        return state == null || state == StreamDebridCacheState.CHECKING
    }

    private fun String?.matchesResolver(activeResolverProviderId: String?): Boolean {
        val active = activeResolverProviderId?.trim().orEmpty()
        return active.isBlank() || this == null || equals(active, ignoreCase = true)
    }

    /** Stable ranking from metadata the addon already supplied. Unknown fields stay neutral. */
    private fun smartOrder(streams: List<StreamItem>, preferredAudioLanguage: String?, balanced: Boolean): List<StreamItem> = streams.withIndex()
        .sortedWith(compareByDescending<IndexedValue<StreamItem>> { if (balanced) BalancedAutoPlayPolicy.sizeRank(it.value) else 0 }
            .thenByDescending { smartScore(it.value, if (balanced) "en" else preferredAudioLanguage) }
            .thenBy { it.index })
        .map { it.value }

    private fun smartScore(stream: StreamItem, preferredAudioLanguage: String?): Int {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val description = listOfNotNull(parsed?.resolution, parsed?.quality, stream.name,
            stream.title, stream.description).joinToString(" ").lowercase()
        var score = when {
            stream.addonId == "$W_CORE_ADDON_ID:jellyfin" -> 10_000
            stream.addonId.startsWith("$W_CORE_ADDON_ID:") -> 5_500
            stream.isDirectDebridStream || stream.isCachedDebridTorrentStream -> 5_000
            stream.playableDirectUrl != null -> 3_000
            else -> 0
        }
        score += when {
            Regex("\\b(2160p|4k|uhd)\\b").containsMatchIn(description) -> 800
            Regex("\\b(1080p|fhd)\\b").containsMatchIn(description) -> 650
            Regex("\\b(720p|hd)\\b").containsMatchIn(description) -> 400
            Regex("\\b(480p|sd)\\b").containsMatchIn(description) -> 150
            else -> 0
        }
        // Do not assume that a device supports Dolby Vision or a particular codec.
        if (parsed?.hdr?.any { it.contains("hdr", true) } == true ||
            Regex("\\b(hdr10|hdr10\\+|hlg)\\b").containsMatchIn(description)) score += 75
        if (Regex("\\b(cam|telesync|telecine)\\b").containsMatchIn(description)) score -= 500
        val preferredLanguage = preferredAudioLanguage?.lowercase()?.takeIf { it.length in 2..3 && it != "device" }
        val knownLanguages = parsed?.languages.orEmpty()
        if (preferredLanguage != null && knownLanguages.isNotEmpty()) {
            score += if (knownLanguages.any { it.lowercase().startsWith(preferredLanguage) }) 100 else -100
        }
        val size = stream.clientResolve?.stream?.raw?.size
        if (size != null && size > 30L * 1024 * 1024 * 1024) score -= 100
        return score
    }
}

data class StreamAutoPlayEvaluation(
    val stream: StreamItem? = null,
    val readyStreams: List<StreamItem> = emptyList(),
    val hasPendingDebridCandidate: Boolean = false,
)
