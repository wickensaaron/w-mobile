package com.nuvio.app.features.livetv

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Display values never replace or modify the source-owned object used by playback. */
data class LiveTvAccountGuideRow(
    val channel: LiveTvChannel,
    val displayName: String,
    val displayGroup: String,
    val channelNumber: Int?,
    val groupKey: String?,
    val alternatives: List<LiveTvChannel> = emptyList(),
) {
    override fun toString(): String = "LiveTvAccountGuideRow(number=$channelNumber, alternatives=${alternatives.size})"
}

data class LiveTvAccountGuidePresentation(
    val rows: List<LiveTvAccountGuideRow> = emptyList(),
    val eligibleChannelCount: Int = 0,
    val channelsWithoutGuide: Int = 0,
    val duplicatesHidden: Int = 0,
    val hiddenChannelCount: Int = 0,
    val rawAlternatives: List<LiveTvChannel> = emptyList(),
) {
    override fun toString(): String = "LiveTvAccountGuidePresentation(rows=${rows.size}, eligible=$eligibleChannelCount)"
}

private val accountGuideSpace = Regex("\\s+")
private val accountGuideHashes = Regex("""^\s*#{2,}\s*|\s*#{2,}\s*$""")
private val accountGuideUkLabel = Regex("(^|[^A-Za-z])(UK|U\\.K\\.|GB|UNITED KINGDOM|GREAT BRITAIN)(?=$|[^A-Za-z])", RegexOption.IGNORE_CASE)
private val accountGuideUkPrefix = Regex(
    """^\s*(?:[.|·-]*\s*(?:UK|U\.K\.|GB|UNITED KINGDOM|GREAT BRITAIN)\s*[=:|·-]\s*|[.\s|·-]*[\[(]\s*(?:UK|U\.K\.|GB|UNITED KINGDOM|GREAT BRITAIN)\s*[\])]\s*[=:|·-]*\s*|🇬🇧\s*[=:|·-]*\s*)""",
    RegexOption.IGNORE_CASE,
)
private val accountGuideRadioGroup = Regex("(^|[^A-Za-z])RADIOS?(?=$|[^A-Za-z])", RegexOption.IGNORE_CASE)
private val accountGuideRadioName = Regex("""\bRADIO\b|(?:^|\s)\d*(?:\.\d+)?\s*(?:FM|AM)$|^\d+FM\b""", RegexOption.IGNORE_CASE)
private val accountGuideQualityToken = Regex("""\b(2160P|1080P|720P|480P|FHD|UHD|4K|HD|SD)\b""", RegexOption.IGNORE_CASE)
private val accountGuideTerminalQuality = Regex("""(?:\s+|\s*[|·:_-]\s*|\s*[\[(]\s*)(2160P|1080P|720P|480P|FHD|UHD|4K|HD|SD)(?:\s*[\])])?\s*\*{0,4}\s*$""", RegexOption.IGNORE_CASE)
private val accountGuideTerminalBackup = Regex("""(?:\s+|\s*[|·:_\-(\[]\s*)(?:BACKUP|BACK UP|ALT|ALTERNATIVE)(?:\s*#?\d+)?\s*[)\]]?\s*$""", RegexOption.IGNORE_CASE)
private val accountGuideTerminalDecoration = Regex("""(?:\s+|\s*[|:_-]\s*|\s*[\[(]\s*)(?:UK|GB|HEVC|H\.?26[45]|50\s*FPS|25\s*FPS|VIP|PREMIUM|RAW)(?:\s*[\])])?\s*$""", RegexOption.IGNORE_CASE)
private val accountGuideTerminalDuplicate = Regex("""\s*[\[(]\s*\d{1,2}\s*[\])]\s*$""")
private val accountGuideTerminalStars = Regex("""\s*\*{1,4}$""")
private val accountGuideBbcAlias = Regex("""^BBC\s*(1|2|3|4|ONE|TWO|THREE|FOUR)(?=$|[\s+|:_(\[-])""", RegexOption.IGNORE_CASE)
private val accountGuideCommercialAlias = Regex("""^(ITV\s*(?:[1-4]|BE)?|CHANNEL\s*4|C4|E\s*4|MORE\s*4|FILM\s*4)(?=$|[\s+|:_(\[-])""", RegexOption.IGNORE_CASE)
private val accountGuideNameKey = Regex("""[^\p{L}\p{N}+]""")
private val accountGuideTerminalShift = Regex("""\s*\+\s*1$""")
private const val accountGuideEligibilityMs = 24L * 60 * 60 * 1000

internal fun accountGuideIsRadioName(name: String): Boolean = accountGuideRadioName.containsMatchIn(name)

/** Country evidence comes from the original provider label/prefix, never the Sky number or station title. */
internal fun isExplicitUkAccountGuideChannel(channel: LiveTvChannel): Boolean {
    val name = channel.name.trim().replace(accountGuideHashes, "")
    return channel.group?.let { accountGuideUkLabel.containsMatchIn(it) || "🇬🇧" in it } == true ||
        accountGuideUkPrefix.containsMatchIn(name) ||
        accountGuideUkLabel.find(name.trimStart(' ', '.', '[', '(', '|', '-', '·'))?.range?.first == 0 ||
        name.startsWith("🇬🇧")
}

private fun originalGuideLabel(value: String, limit: Int): String = value.trim().replace(accountGuideSpace, " ").take(limit)

/** A catalog ID may be reindexed. Require original labels too; never search other IDs for a likely match. */
internal fun accountGuideCatalogGroupKey(channel: LiveTvChannel, snapshot: LiveTvAccountGuideSnapshot): String? {
    if (!snapshot.hasCatalog) return null
    val source = channel.playlistId ?: return null
    val binding = snapshot.catalogBindingsByChannelId[channel.id] ?: return null
    if (canonicalAccountGuideProvider(channel.id) != source || binding.providerId != source ||
        snapshot.groupKeysByChannelId[channel.id] != binding.groupKey || binding.groupKey.length != 64 ||
        binding.groupKey.any { it !in 'a'..'f' && it !in '0'..'9' }) return null
    val originalName = originalGuideLabel(channel.name, 160)
    val originalGroup = originalGuideLabel(channel.group.orEmpty(), 120).ifEmpty { "Other" }
    return binding.groupKey.takeIf {
        originalName == originalGuideLabel(binding.channelName, 160) &&
            originalGroup == originalGuideLabel(binding.groupName, 120)
    }
}

private fun accountGuideAliasName(name: String): String {
    val bbc = accountGuideBbcAlias.find(name)
    if (bbc != null) {
        val base = when (bbc.groupValues[1].uppercase()) {
            "1", "ONE" -> "BBC One"
            "2", "TWO" -> "BBC Two"
            "3", "THREE" -> "BBC Three"
            else -> "BBC Four"
        }
        return (base + " " + name.substring(bbc.range.last + 1).trim()).trim()
    }
    val commercial = accountGuideCommercialAlias.find(name) ?: return name
    val token = commercial.groupValues[1].replace(" ", "").uppercase()
    val base = when (token) {
        "ITV", "ITV1" -> "ITV1"
        "ITVBE" -> "ITVBe"
        "CHANNEL4", "C4" -> "Channel 4"
        "MORE4" -> "More4"
        "FILM4" -> "Film4"
        else -> token
    }
    return (base + " " + name.substring(commercial.range.last + 1).trim()).trim()
}

private fun accountGuideQuality(name: String): Int = accountGuideQualityToken.findAll(name).maxOfOrNull {
    when (it.groupValues[1].uppercase()) {
        "UHD", "4K", "2160P" -> 4
        "FHD", "1080P" -> 3
        "HD", "720P" -> 2
        else -> 1
    }
} ?: 0

private data class AccountGuideDisplay(val name: String, val radio: Boolean, val known: Boolean, val broadcastKey: String,
    val regionalFamily: String?, val national: Boolean)

private fun accountGuideDisplay(channel: LiveTvChannel, uk: Boolean, collapseAlternatives: Boolean): AccountGuideDisplay {
    val radio = channel.group?.let(accountGuideRadioGroup::containsMatchIn) == true || accountGuideIsRadioName(channel.name)
    var original = channel.name.trim().replace(accountGuideSpace, " ").replace(accountGuideHashes, "").trim('|', '·', ' ')
    if (uk) original = accountGuideUkPrefix.replaceFirst(original, "").trim()
    val aliased = if (uk && !radio) accountGuideAliasName(original) else original
    var cleaned = aliased
    var previous: String
    do {
        previous = cleaned
        cleaned = accountGuideTerminalQuality.replace(cleaned, "").trim(' ', '|', '·', ':', '_', '-')
        if (collapseAlternatives) {
            cleaned = accountGuideTerminalBackup.replace(cleaned, "").trim(' ', '|', '·', ':', '_', '-')
            cleaned = accountGuideTerminalDecoration.replace(cleaned, "").trim()
            cleaned = accountGuideTerminalDuplicate.replace(cleaned, "").trim()
            cleaned = accountGuideTerminalStars.replace(cleaned, "").trim()
        }
    } while (previous != cleaned)
    cleaned = cleaned.replace(accountGuideTerminalShift, " +1")
    val family = if (uk && !radio) accountGuideRegionalFamily(cleaned) else null
    // Unknown suffixes/services keep their full original title and remain separate.
    val known = uk && !radio && (accountGuideExactSkyQNumber(cleaned) != null || family != null)
    val name = if (known) cleaned else original
    val key = if (known) name.lowercase().replace(accountGuideNameKey, "") else "raw:" + name.lowercase()
    return AccountGuideDisplay(name, radio, known, key, family?.first, family?.second == true)
}

private data class AccountGuideCandidate(
    val channel: LiveTvChannel,
    val display: AccountGuideDisplay,
    val groupKey: String?,
    val group: String,
    val sourceRank: Int,
    val sourceOrder: Int,
    val quality: Int,
    val hasGuide: Boolean,
) {
    val broadcastKey: String = "$group|${display.radio}|${display.broadcastKey}"
}

private class AccountGuideCheckpoint {
    private var operations = 0
    suspend fun check() {
        if (operations++ % 256 == 0) {
            currentCoroutineContext().ensureActive()
            yield()
        }
    }
}

/**
 * Worker-only projection of the imported account lane. Caller dispatches to Default and fences
 * publication by owner, source generation and input snapshots. Every returned channel is untouched.
 */
internal suspend fun projectLiveTvAccountGuide(
    rawChannels: List<LiveTvChannel>,
    programmes: Map<String, List<LiveTvProgramme>>,
    snapshot: LiveTvAccountGuideSnapshot,
    sourcePriority: List<String>,
    owner: LiveTvAccountScope,
    accountSourceGeneration: Long,
    nowMs: Long,
    favoritesOnly: Boolean = false,
): LiveTvAccountGuidePresentation {
    require(rawChannels.size <= MobileLiveTvCatalogueLimit && programmes.size <= MobileLiveTvProgrammeLimit)
    require(sourcePriority.size <= 100 && sourcePriority.distinct().size == sourcePriority.size &&
        sourcePriority.all(accountGuideProviderPattern::matches))
    require(owner.profile in 1..6 && owner.account.isNotBlank() && accountSourceGeneration >= 0 &&
        nowMs >= 0 && nowMs <= Long.MAX_VALUE - accountGuideEligibilityMs)
    val preferences = snapshot.preferences ?: LiveTvAccountGuidePreferences()
    validateLiveTvAccountGuidePreferences(preferences)
    val checkpoint = AccountGuideCheckpoint()
    checkpoint.check()
    val priorities = sourcePriority.withIndex().associate { it.value to it.index }
    val owned = ArrayList<LiveTvChannel>()
    val identityCounts = mutableMapOf<String, Int>()
    for (channel in rawChannels) {
        checkpoint.check()
        val providerId = channel.playlistId
        if (channel.accountScope == owner && channel.accountSourceGeneration == accountSourceGeneration &&
            providerId != null && providerId in priorities && canonicalAccountGuideProvider(channel.id) == providerId) {
            owned += channel
            identityCounts[channel.id] = (identityCounts[channel.id] ?: 0) + 1
        }
    }
    val ownedIds = identityCounts.filterValues { it == 1 }.keys
    val guided = mutableSetOf<String>()
    var programmeReads = 0
    for ((id, rows) in programmes) {
        checkpoint.check()
        if (id !in ownedIds) continue
        for (row in rows) {
            checkpoint.check()
            require(++programmeReads <= MobileLiveTvProgrammeLimit) { "Guide projection exceeds device limit" }
            if (row.channelId == id && row.title.isNotBlank() && row.stopEpochMs > nowMs &&
                row.stopEpochMs > row.startEpochMs && row.startEpochMs < nowMs + accountGuideEligibilityMs) guided += id
        }
    }
    val hidden = preferences.hiddenGroupKeys.toHashSet()
    val saved = preferences.favouriteIds.toHashSet()
    val groupMemo = mutableMapOf<Pair<String, String>, String>()
    val candidates = ArrayList<AccountGuideCandidate>()
    var withoutGuide = 0
    for ((index, channel) in owned.withIndex()) {
        checkpoint.check()
        if (channel.id !in ownedIds) continue
        val uk = isExplicitUkAccountGuideChannel(channel)
        if (preferences.ukOnly && !uk) continue
        val source = channel.playlistId!!
        val rawGroup = channel.group.orEmpty()
        val catalogKey = accountGuideCatalogGroupKey(channel, snapshot)
        // "Other" can be a parser fallback or a real provider category: only a verified catalog disambiguates it.
        val key = catalogKey ?: rawGroup.takeIf { it.isNotBlank() && !it.trim().equals("Other", true) }?.let {
            groupMemo.getOrPut(source to it) { liveTvAccountGuideGroupKey(source, it) }
        }
        val display = accountGuideDisplay(channel, uk, preferences.collapseRegional)
        val hasGuide = channel.id in guided
        if (!hasGuide) withoutGuide++
        candidates += AccountGuideCandidate(channel, display, key,
            if (uk) "All UK" else rawGroup.ifBlank { "Other" }, priorities.getValue(source), index,
            accountGuideQuality(channel.name), hasGuide)
    }
    val eligible = candidates.filter { it.hasGuide }
    // TV rule: category hiding affects All; exact saved favourites remain reachable.
    val visible = eligible.filter { if (favoritesOnly) it.channel.id in saved else it.groupKey?.let { key -> key !in hidden } ?: true }
    val selected = linkedMapOf<String, AccountGuideCandidate>()
    for (candidate in visible) {
        checkpoint.check()
        val key = if (preferences.autoTidy && candidate.display.known) candidate.broadcastKey else "id:" + candidate.channel.id
        val previous = selected[key]
        if (previous == null || accountGuideCandidateWins(candidate, previous)) selected[key] = candidate
    }
    val qualityWinners = selected.values.toList()
    val regionalWinners = mutableMapOf<String, AccountGuideCandidate>()
    if (preferences.autoTidy && preferences.ukOnly && preferences.collapseRegional && !favoritesOnly) {
        for (candidate in qualityWinners) {
            checkpoint.check()
            val family = candidate.display.regionalFamily ?: continue
            val previous = regionalWinners[family]
            if (previous == null || candidate.display.national && !previous.display.national ||
                candidate.display.national == previous.display.national && accountGuideCandidateWins(candidate, previous)) {
                regionalWinners[family] = candidate
            }
        }
    }
    val collapsed = qualityWinners.filter { candidate ->
        candidate.display.regionalFamily?.let { family -> regionalWinners[family]?.let { it === candidate } } ?: true
    }
    val order = preferences.orderedGroupKeys.withIndex().associate { it.value to it.index }
    val numbered = collapsed.map { it to if (it.display.radio) null else accountGuideSkyQNumber(it.display.name) }
    val rowOrder = compareBy<Pair<AccountGuideCandidate, Int?>>(
        { it.first.groupKey?.let(order::get) ?: Int.MAX_VALUE },
        { if (preferences.ukOnly) it.second ?: Int.MAX_VALUE else Int.MAX_VALUE },
        { if (preferences.ukOnly) "" else it.first.group.lowercase() },
        { if (it.second == null || !preferences.ukOnly) it.first.display.name.lowercase() else "" },
        { it.first.sourceOrder },
    )
    val workerContext = currentCoroutineContext()
    var comparisons = 0
    val sorted = numbered.sortedWith(Comparator { left, right ->
        if (comparisons++ % 256 == 0) workerContext.ensureActive()
        rowOrder.compare(left, right)
    })
    checkpoint.check()
    val alternates = linkedMapOf<String, MutableList<AccountGuideCandidate>>()
    for (candidate in candidates) {
        checkpoint.check()
        // Unknown titles have no asserted equivalence and cannot become source-switch alternatives.
        if (candidate.display.known) alternates.getOrPut(candidate.broadcastKey) { mutableListOf() } += candidate
    }
    val rows = ArrayList<LiveTvAccountGuideRow>(sorted.size)
    val alternateOrder = compareByDescending<AccountGuideCandidate> { it.hasGuide }
        .thenBy { it.sourceRank }.thenByDescending { it.quality }.thenBy { it.sourceOrder }
    val neededBroadcasts = sorted.mapTo(HashSet()) { it.first.broadcastKey }
    val alternativesByBroadcast = mutableMapOf<String, List<LiveTvChannel>>()
    for ((key, sourceRows) in alternates) {
        checkpoint.check()
        if (key !in neededBroadcasts) continue
        val sortedAlternates = sourceRows.sortedWith(Comparator { left, right ->
            if (comparisons++ % 256 == 0) workerContext.ensureActive()
            alternateOrder.compare(left, right)
        })
        val sources = ArrayList<LiveTvChannel>(sortedAlternates.size)
        for (alternate in sortedAlternates) {
            checkpoint.check()
            sources += alternate.channel
        }
        alternativesByBroadcast[key] = sources.toList()
    }
    for ((candidate, number) in sorted) {
        checkpoint.check()
        rows += LiveTvAccountGuideRow(candidate.channel,
            if (preferences.autoTidy) candidate.display.name else candidate.channel.name,
            candidate.group, if (preferences.ukOnly) number else null, candidate.groupKey,
            alternativesByBroadcast[candidate.broadcastKey].orEmpty())
    }
    val result = LiveTvAccountGuidePresentation(rows.toList(), eligible.size, withoutGuide, visible.size - rows.size,
        if (favoritesOnly) 0 else eligible.size - visible.size, owned.filter { it.id in ownedIds })
    currentCoroutineContext().ensureActive()
    return result
}

private fun accountGuideCandidateWins(candidate: AccountGuideCandidate, previous: AccountGuideCandidate): Boolean =
    candidate.hasGuide && !previous.hasGuide || candidate.hasGuide == previous.hasGuide &&
        (candidate.sourceRank < previous.sourceRank || candidate.sourceRank == previous.sourceRank &&
            (candidate.quality > previous.quality || candidate.quality == previous.quality && candidate.sourceOrder < previous.sourceOrder))

/** Search is deliberately independent of expensive ownership/EPG/tidy preparation. */
internal fun filterLiveTvAccountGuideRows(rows: List<LiveTvAccountGuideRow>, searchQuery: String): List<LiveTvAccountGuideRow> {
    val query = searchQuery.trim()
    return if (query.isEmpty()) rows else rows.filter {
        it.displayName.contains(query, true) || it.displayGroup.contains(query, true) ||
            it.channelNumber?.toString()?.contains(query) == true
    }
}
