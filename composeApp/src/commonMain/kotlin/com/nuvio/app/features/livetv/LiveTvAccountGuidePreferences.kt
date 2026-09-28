package com.nuvio.app.features.livetv

import com.nuvio.app.features.profiles.ProfilePinCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** The existing organiser v1 contract; source credentials and playback addresses are never included. */
@Serializable
data class LiveTvAccountGuidePreferences(
    val version: Int = 1,
    val hiddenGroupKeys: List<String> = emptyList(),
    val orderedGroupKeys: List<String> = emptyList(),
    val favouriteIds: List<String> = emptyList(),
    val autoTidy: Boolean = true,
    val ukOnly: Boolean = true,
    val collapseRegional: Boolean = true,
)

/** A read-only profile snapshot. Its caller must bind publication to backend/account/profile. */
data class LiveTvAccountGuideSnapshot(
    val revision: Long = 0,
    val catalogRevision: Long = 0,
    val preferences: LiveTvAccountGuidePreferences? = null,
    val groupKeysByChannelId: Map<String, String> = emptyMap(),
    val hasCatalog: Boolean = false,
    val catalogBindingsByChannelId: Map<String, LiveTvAccountGuideCatalogBinding> = emptyMap(),
)

data class LiveTvAccountGuideCatalogBinding(
    val providerId: String,
    val channelName: String,
    val groupName: String,
    val groupKey: String,
)

internal const val LiveTvAccountGuideWrapperBytes = 13 * 1024 * 1024
internal const val LiveTvAccountGuideCatalogBytes = 12 * 1024 * 1024
internal const val LiveTvAccountGuidePreferenceBytes = 256 * 1024
internal const val LiveTvAccountGuideCatalogChannels = 50_000
internal val accountGuideProviderPattern =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
internal val accountGuideChannelSuffixPattern = Regex("[A-Za-z0-9_-]{1,80}")
private val accountGuideGroupPattern = Regex("[a-f0-9]{64}")
private val accountGuideContractSourcePattern = Regex("[A-Za-z0-9_-]{1,80}")
private val accountGuideWhitespace = Regex("\\s+")
private val accountGuideUnsafeDisplay = Regex("https?://|[\\p{Cc}]", RegexOption.IGNORE_CASE)
private val accountGuideJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class GuidePullEnvelope(val revision: Long, val catalogRevision: Long)
@Serializable
private data class GuideCatalog(val groups: List<GuideCatalogGroup> = emptyList(), val channels: List<GuideCatalogChannel> = emptyList())
@Serializable
private data class GuideCatalogGroup(val key: String, val name: String, val providerId: String)
@Serializable
private data class GuideCatalogChannel(val id: String, val name: String, val groupKey: String)

/** Validate before decoding/allocating a second large JSON representation. No UTF-8 byte array is needed. */
internal fun requireAccountGuideUtf8Budget(value: String, maxBytes: Int) {
    require(maxBytes >= 0 && value.length <= maxBytes) { "Guide payload exceeds device limit" }
    var bytes = 0L
    var index = 0
    while (index < value.length) {
        val code = value[index].code
        bytes += when {
            code < 0x80 -> 1
            code < 0x800 -> 2
            code in 0xD800..0xDBFF && index + 1 < value.length && value[index + 1].code in 0xDC00..0xDFFF -> {
                index++
                4
            }
            else -> 3
        }
        require(bytes <= maxBytes) { "Guide payload exceeds device limit" }
        index++
    }
}

internal fun validateLiveTvAccountGuidePreferences(value: LiveTvAccountGuidePreferences) {
    require(value.version == 1) { "Unsupported guide preferences" }
    listOf(value.hiddenGroupKeys, value.orderedGroupKeys).forEach { keys ->
        require(keys.size <= 1_000 && keys.distinct().size == keys.size && keys.all(accountGuideGroupPattern::matches)) {
            "Invalid guide category preferences"
        }
    }
    require(value.favouriteIds.size <= 1_000 && value.favouriteIds.distinct().size == value.favouriteIds.size &&
        value.favouriteIds.all { accountGuideContractProvider(it) != null }) { "Invalid guide favourites" }
}

/** Organiser v1 also permits safe legacy/local source IDs; these never grant imported playback ownership. */
private fun accountGuideContractProvider(id: String): String? {
    val source = id.substringBefore(':', "")
    val suffix = id.substringAfter(':', "")
    return source.takeIf { accountGuideContractSourcePattern.matches(it) && accountGuideChannelSuffixPattern.matches(suffix) }
}

internal fun canonicalAccountGuideProvider(id: String): String? {
    val source = id.substringBefore(':', "")
    val suffix = id.substringAfter(':', "")
    return source.takeIf { accountGuideProviderPattern.matches(it) && accountGuideChannelSuffixPattern.matches(suffix) }
}

internal fun requireAccountGuideCatalogCounts(groups: Int, channels: Int) {
    require(groups in 0..1_000 && channels in 0..LiveTvAccountGuideCatalogChannels) { "Guide catalog exceeds device limit" }
}

/** Mirrors the organiser's digest of the original source/category pair, never a cleaned display name. */
internal fun liveTvAccountGuideGroupKey(providerId: String, originalGroup: String): String {
    require(accountGuideProviderPattern.matches(providerId))
    val group = originalGroup.trim().replace(accountGuideWhitespace, " ").lowercase()
    return ProfilePinCrypto.sha256Hex("${providerId.length}:$providerId${group.length}:$group")
}

/**
 * Existing RPC response only. Catalog names are validated, but never used to guess UK eligibility,
 * category identity or playback identity. Well-formed stale providers are omitted from the usable map.
 */
internal fun decodeLiveTvAccountGuideSnapshot(
    payload: String,
    enabledProviderIds: Set<String>,
): LiveTvAccountGuideSnapshot {
    require(enabledProviderIds.size <= 100 && enabledProviderIds.all(accountGuideProviderPattern::matches))
    requireAccountGuideUtf8Budget(payload, LiveTvAccountGuideWrapperBytes)
    val root = accountGuideJson.parseToJsonElement(payload) as? JsonObject ?: error("Invalid guide snapshot")
    require("preferences" in root) { "Incomplete guide snapshot" }
    val envelope = accountGuideJson.decodeFromJsonElement<GuidePullEnvelope>(root)
    require(envelope.revision >= 0 && envelope.catalogRevision >= 0) { "Invalid guide revision" }
    val rawPreferences = root["preferences"]?.takeUnless { it == JsonNull }
    val preferences = rawPreferences?.let {
        val objectValue = it as? JsonObject ?: error("Invalid guide preferences")
        require(objectValue.keys == setOf("version", "hiddenGroupKeys", "orderedGroupKeys",
            "favouriteIds", "autoTidy", "ukOnly", "collapseRegional")) { "Invalid guide preference fields" }
        requireAccountGuideUtf8Budget(objectValue.toString(), LiveTvAccountGuidePreferenceBytes)
        accountGuideJson.decodeFromJsonElement<LiveTvAccountGuidePreferences>(objectValue)
            .also(::validateLiveTvAccountGuidePreferences)
    }
    val rawCatalog = root["catalog"]?.takeUnless { it == JsonNull }
    if (rawCatalog == null) return LiveTvAccountGuideSnapshot(envelope.revision, envelope.catalogRevision, preferences)
    val catalogObject = rawCatalog as? JsonObject ?: error("Invalid guide catalog")
    require(catalogObject.keys.all { it == "groups" || it == "channels" }) { "Invalid guide catalog fields" }
    requireAccountGuideUtf8Budget(catalogObject.toString(), LiveTvAccountGuideCatalogBytes)
    val groupCount = (catalogObject["groups"] as? kotlinx.serialization.json.JsonArray)?.size ?: error("Invalid guide categories")
    val channelCount = (catalogObject["channels"] as? kotlinx.serialization.json.JsonArray)?.size ?: error("Invalid guide channels")
    requireAccountGuideCatalogCounts(groupCount, channelCount)
    val catalog = accountGuideJson.decodeFromJsonElement<GuideCatalog>(catalogObject)
    requireAccountGuideCatalogCounts(catalog.groups.size, catalog.channels.size)
    val groups = mutableMapOf<String, String>()
    for (group in catalog.groups) {
        require(accountGuideGroupPattern.matches(group.key) && accountGuideContractSourcePattern.matches(group.providerId) &&
            group.name.isNotBlank() && group.name.length <= 120 && !accountGuideUnsafeDisplay.containsMatchIn(group.name) &&
            groups.put(group.key, group.providerId) == null) { "Invalid guide category binding" }
    }
    val ids = mutableSetOf<String>()
    val usable = linkedMapOf<String, String>()
    val bindings = linkedMapOf<String, LiveTvAccountGuideCatalogBinding>()
    val groupNames = catalog.groups.associate { it.key to it.name }
    for (channel in catalog.channels) {
        val source = accountGuideContractProvider(channel.id)
        require(source != null && groups[channel.groupKey] == source && ids.add(channel.id) &&
            channel.name.isNotBlank() && channel.name.length <= 160 && !accountGuideUnsafeDisplay.containsMatchIn(channel.name)) {
            "Invalid guide channel binding"
        }
        if (source in enabledProviderIds) {
            usable[channel.id] = channel.groupKey
            bindings[channel.id] = LiveTvAccountGuideCatalogBinding(source, channel.name,
                groupNames.getValue(channel.groupKey), channel.groupKey)
        }
    }
    return LiveTvAccountGuideSnapshot(envelope.revision, envelope.catalogRevision, preferences, usable.toMap(), true, bindings.toMap())
}
