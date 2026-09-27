package com.nuvio.app.core.network

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watching.sync.currentNuvioSyncIdentity
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal fun isWCoreMediaId(id: String): Boolean = id.matches(Regex("wm_[A-Za-z0-9_-]{8,64}"))

internal data class WCoreLibraryOwner(val account: String, val profileId: Int, val origin: String, val revision: Long)
internal data class WCoreLibraryScope(val account: String, val profileId: Int, val origin: String, val token: String, val revision: Long = 0L) {
    val owner: WCoreLibraryOwner get() = WCoreLibraryOwner(account, profileId, origin, revision)
}

internal fun WCoreLibraryScope.sameOwner(other: WCoreLibraryScope?): Boolean = other != null &&
    account == other.account && profileId == other.profileId && origin == other.origin && revision == other.revision

/** Stable logical artwork ownership; never includes a Core access token or image ticket. */
internal fun coreArtworkScope(session: WCoreLibraryScope): String =
    "${session.account}|${session.profileId}|${session.origin}".encodeToByteArray()
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal fun nativeCorePlayable(id: String, item: WCoreLibraryItem?, owner: WCoreLibraryOwner?, current: WCoreLibraryScope?): Boolean =
    isWCoreMediaId(id) && item?.id == id && owner != null && owner == current?.owner

/** Signed images are held only in this account-owned, in-memory inventory. */
internal data class WCoreLibraryItem(
    val id: String,
    val type: String,
    val title: String,
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val posterTicket: String? = null,
    val backdropTicket: String? = null,
    val imageExpiresAt: Instant? = null,
    val seriesWMediaId: String? = null,
    val jellyfinSeriesId: String? = null,
    val seriesMembershipStatus: String? = null,
)

internal fun coreImageTicket(value: String?, origin: String): String? = value?.takeIf {
    it.startsWith("$origin/api/wcore/image/") &&
        !it.any { char -> char.isWhitespace() || char == '\\' } &&
        it.length <= 4096
}

internal fun parseWCoreLibrary(body: String, origin: String): List<WCoreLibraryItem> {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
    return (root["items"] as? JsonArray).orEmpty().take(5000).mapNotNull { value ->
        val row = value as? JsonObject ?: return@mapNotNull null
        val id = row.text("wMediaId") ?: row.text("mediaId") ?: return@mapNotNull null
        if (!isWCoreMediaId(id)) return@mapNotNull null
        val type = row.text("type") ?: return@mapNotNull null
        if (type !in setOf("movie", "episode")) return@mapNotNull null
        val season = row.text("season")?.toIntOrNull()
        val episode = row.text("episode")?.toIntOrNull()
        if (type == "episode" && (season == null || season < 0 || episode == null || episode < 1)) return@mapNotNull null
        val title = row.text("title")?.take(300) ?: return@mapNotNull null
        val jellyfin = row["jellyfin"] as? JsonObject
        WCoreLibraryItem(
            id, type, title, row.text("year"), season, episode, row.text("episodeTitle")?.take(300),
            coreImageTicket(row.text("posterUrl"), origin),
            coreImageTicket(row.text("backdropUrl"), origin),
            jellyfin?.text("playbackExpiresAt")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            row.text("seriesWMediaId")?.takeIf(::isWCoreMediaId),
            row.text("jellyfinSeriesId")?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) },
            row.text("seriesMembershipStatus")?.take(128),
        )
    }.distinctBy { it.id }
}

internal class WCoreNativeClient(
    private val scope: () -> WCoreLibraryScope?,
    private val request: suspend (WCoreLibraryScope, String) -> RawHttpResponse = { session, path ->
        httpRequestRaw("GET", session.origin + path,
            mapOf("Authorization" to "Bearer ${session.token}", "Accept" to "application/json"), "",
            followRedirects = false, maxResponseBodyBytes = 8 * 1024 * 1024)
    },
) {
    private suspend fun call(session: WCoreLibraryScope, path: String): String {
        currentCoroutineContext().ensureActive()
        check(scope() == session) { "Core account changed" }
        val response = request(session, path)
        currentCoroutineContext().ensureActive()
        check(scope() == session) { "Core account changed" }
        check(response.status in 200..299) { "Core unavailable" }
        return response.body
    }

    suspend fun library(session: WCoreLibraryScope): List<WCoreLibraryItem> {
        val bootstrap = Json.parseToJsonElement(call(session, "/api/v1/bootstrap")) as? JsonObject
        val capabilities = bootstrap?.get("capabilities") as? JsonObject
        val providers = capabilities?.get("providers") as? JsonObject
        val jellyfin = providers?.get("jellyfin") as? JsonObject
        if (jellyfin?.text("enabled") != "true") return emptyList()
        return parseWCoreLibrary(call(session, "/api/wcore/library"), session.origin)
    }

    suspend fun series(session: WCoreLibraryScope, seriesId: String): WCoreSeriesMembership =
        loadWCoreSeriesMembership(seriesId) { cursor ->
            call(session, "/api/wcore/library/series/$seriesId/episodes?limit=$NativeSeriesPageSize" +
                (cursor?.let { "&cursor=$it" } ?: ""))
        }

    suspend fun detail(session: WCoreLibraryScope, item: WCoreLibraryItem): JsonObject? {
        val coordinates = if (item.type == "episode") "&season=${item.season}&episode=${item.episode}" else ""
        return try {
            Json.parseToJsonElement(call(session,
                "/api/wcore/metadata/detail?type=${item.type}&wMediaId=${item.id}$coordinates")) as? JsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }
}

/** Inventory fallback keeps native Jellyfin items playable without external metadata IDs. */
internal fun nativeCoreDetails(item: WCoreLibraryItem, enriched: JsonObject?, art: (String) -> String?): MetaDetails {
    val richIdentity = enriched?.get("identity") as? JsonObject
    val rich = enriched?.takeIf { richIdentity?.text("wMediaId")?.let { it == item.id } != false }
    val artwork = rich?.get("artwork") as? JsonObject
    val ratings = rich?.get("ratings") as? JsonObject
    // Enrichment is optional. Never accept credential-bearing Core URLs into metadata.
    fun publicArt(key: String): String? = artwork?.text(key)?.takeIf {
        it.startsWith("https://") && "/api/wcore/" !in it && "/api/v1/" !in it && '?' !in it && '@' !in it
    }
    val episodeVideo = if (item.type == "episode") listOf(MetaVideo(
        id = item.id, title = item.episodeTitle ?: item.title,
        season = item.season, episode = item.episode,
        overview = rich?.text("overview"),
    )) else emptyList()
    return MetaDetails(
        id = item.id, type = item.type, name = rich?.text("title") ?: item.title,
        poster = publicArt("poster") ?: art("primary"),
        background = publicArt("backdrop") ?: art("backdrop"),
        logo = publicArt("logo"), description = rich?.text("overview"),
        releaseInfo = rich?.text("year") ?: item.year,
        imdbRating = ratings?.text("tmdb"),
        defaultVideoId = item.id, videos = episodeVideo,
        genres = (rich?.get("genres") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
    )
}

internal object WCoreNativeLibrary {
    private val lock = SynchronizedObject()
    private val refreshMutex = Mutex()
    private val seriesMutex = Mutex()
    private data class CachedSeries(val membership: WCoreSeriesMembership, val loadedAt: Instant)
    private val seriesCache = LinkedHashMap<String, CachedSeries>()
    private val _items = MutableStateFlow<List<MetaPreview>>(emptyList())
    val items = _items.asStateFlow()
    private var owner: WCoreLibraryOwner? = null
    private var artScope = ""
    private var inventory = emptyMap<String, WCoreLibraryItem>()
    private var refreshedAt: Instant? = null
    private val client = WCoreNativeClient(::currentScope)

    internal fun currentScope(): WCoreLibraryScope? {
        val account = currentNuvioSyncIdentity() ?: return null
        val (origin, token) = WCoreConnectionRepository.currentConnection() ?: return null
        if (currentNuvioSyncIdentity() != account) return null
        return WCoreLibraryScope(account, ProfileRepository.activeProfileId, origin, token, WCoreConnectionRepository.connectionRevision())
    }

    fun clear() = synchronized(lock) {
        owner = null; inventory = emptyMap(); seriesCache.clear(); artScope = ""; refreshedAt = null; _items.value = emptyList()
    }

    suspend fun refresh(force: Boolean = false) = refreshMutex.withLock {
        val session = currentScope() ?: run { clear(); return@withLock }
        val now = Clock.System.now()
        synchronized(lock) {
            if (owner != session.owner) clear()
            if (!force && refreshedAt?.let { now - it < 4.minutes } == true) return@withLock
        }
        val records = try {
            withTimeoutOrNull(25_000) { client.library(session) } ?: return@withLock
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Throwable) { return@withLock }
        if (currentScope() != session) return@withLock
        synchronized(lock) {
            if (currentScope() != session) return@synchronized
            owner = session.owner
            artScope = coreArtworkScope(session)
            inventory = records.associateBy { it.id }
            refreshedAt = now
            _items.value = records.take(18).map { item ->
                MetaPreview(item.id, item.type,
                    if (item.type == "episode") "${item.title} · S${item.season} E${item.episode}" else item.title,
                    poster = imageReferenceLocked(item, "primary"), banner = imageReferenceLocked(item, "backdrop"), releaseInfo = item.year)
            }
        }
    }

    fun canPlay(id: String): Boolean = synchronized(lock) {
        nativeCorePlayable(id, itemLocked(id), owner, currentScope())
    }

    suspend fun details(type: String, id: String): MetaDetails? {
        if (!isWCoreMediaId(id)) return null
        if (type == "series") {
            loadSeries(id)
            return fallbackDetails(type, id)
        }
        if (fallbackDetails(type, id) == null) withTimeoutOrNull(4_000) { refresh() }
        return fallbackDetails(type, id)
    }

    fun fallbackDetails(type: String, id: String): MetaDetails? {
        val session = currentScope() ?: return null
        return synchronized(lock) {
            if (owner != session.owner) return@synchronized null
            if (type == "series") return@synchronized seriesDetailsLocked(id)
            val item = itemLocked(id)?.takeIf { it.type == type } ?: return@synchronized null
            item.seriesWMediaId?.takeIf { item.seriesMembershipStatus == "verified" }?.let { parent ->
                seriesCache[parent]?.membership?.takeIf { verifiedSeriesContainsEpisode(it, item) }?.let {
                    seriesDetailsLocked(parent, item.id)?.let { meta -> return@synchronized meta }
                }
            }
            nativeCoreDetails(item, null) { imageReferenceLocked(item, it) }
        }
    }

    suspend fun enrichDetails(type: String, id: String): MetaDetails? {
        val session = currentScope() ?: return null
        if (type == "series") { loadSeries(id); return fallbackDetails(type, id) }
        val item = synchronized(lock) { itemLocked(id)?.takeIf { owner == session.owner && it.type == type } } ?: return null
        if (item.type == "episode" && item.seriesMembershipStatus == "verified" && item.seriesWMediaId != null) {
            seriesForEpisode(item.id)?.let { return it }
        }
        val rich = withTimeoutOrNull(8_000) { client.detail(session, item) }
        if (currentScope() != session) return null
        return synchronized(lock) {
            if (owner != session.owner) null else nativeCoreDetails(item, rich) { imageReferenceLocked(item, it) }
        }
    }


    /** Membership is process-only and never published until every snapshot page is verified. */
    private suspend fun loadSeries(seriesId: String): WCoreSeriesMembership? {
        if (!isWCoreMediaId(seriesId)) return null
        currentCoroutineContext().ensureActive()
        return try {
            withTimeoutOrNull(45_000) {
                seriesMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    val session = currentScope() ?: return@withLock null
                    synchronized(lock) {
                        if (owner == session.owner) seriesCache[seriesId]?.takeIf { Clock.System.now() - it.loadedAt < 4.minutes }
                            ?.let { return@withLock it.membership }
                    }
                    val membership = client.series(session, seriesId)
                    currentCoroutineContext().ensureActive()
                    if (currentScope() != session) return@withLock null
                    synchronized(lock) {
                        if (currentScope() != session) return@synchronized null
                        if (owner != session.owner) clear()
                        owner = session.owner
                        artScope = coreArtworkScope(session)
                        seriesCache[seriesId] = CachedSeries(membership, Clock.System.now())
                        while (seriesCache.size > 12 || seriesCache.values.sumOf { it.membership.members.size } > 20_000)
                            seriesCache.remove(seriesCache.keys.first())
                        membership
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Throwable) { null }
    }

    suspend fun seriesForEpisode(episodeId: String): MetaDetails? {
        val session = currentScope() ?: return null
        val episode = synchronized(lock) { itemLocked(episodeId)?.takeIf { owner == session.owner && it.type == "episode" && it.seriesMembershipStatus == "verified" } } ?: return null
        val parentId = episode.seriesWMediaId ?: return null
        val membership = loadSeries(parentId) ?: return null
        currentCoroutineContext().ensureActive()
        if (!isOwnerCurrent(session) || !verifiedSeriesContainsEpisode(membership, episode)) return null
        return synchronized(lock) { if (owner == session.owner && currentScope()?.owner == session.owner) seriesDetailsLocked(parentId, episodeId) else null }
    }

    fun hasOwnedSeries(id: String): Boolean = synchronized(lock) { owner == currentScope()?.owner && seriesCache.containsKey(id) }

    fun isOwnedSeriesMember(seriesId: String, episodeId: String?, season: Int?, episode: Int?): Boolean = synchronized(lock) {
        owner == currentScope()?.owner && seriesCache[seriesId]?.membership?.members?.any {
            it.id == episodeId && it.season == season && it.episode == episode
        } == true
    }

    private fun seriesDetailsLocked(seriesId: String, focusedEpisodeId: String? = null): MetaDetails? {
        val membership = seriesCache[seriesId]?.membership ?: return null
        val seed = focusedEpisodeId?.let(inventory::get)?.takeIf { verifiedSeriesContainsEpisode(membership, it) }
            ?: inventory.values.firstOrNull { verifiedSeriesContainsEpisode(membership, it) && membership.members.any { member -> member.id == it.id } }
        val metadata = seed?.let { nativeCoreDetails(it, null) { kind -> imageReferenceLocked(it, kind) } }
        return nativeCoreSeriesDetails(membership, focusedEpisodeId, metadata)
    }

    private fun itemLocked(id: String): WCoreLibraryItem? = inventory[id] ?: seriesCache.values.firstNotNullOfOrNull { cached ->
        cached.membership.members.firstOrNull { it.id == id }?.let { member ->
            WCoreLibraryItem(member.id, "episode", cached.membership.title, season = member.season, episode = member.episode,
                episodeTitle = member.title, seriesWMediaId = cached.membership.id, jellyfinSeriesId = cached.membership.jellyfinSeriesId,
                seriesMembershipStatus = "verified")
        }
    }

    private fun imageReferenceLocked(item: WCoreLibraryItem, kind: String): String? =
        (if (kind == "primary") item.posterTicket else item.backdropTicket)?.let { "wcore-art://$artScope/${item.id}/$kind" }

    suspend fun imageTicket(reference: String): Pair<WCoreLibraryScope, String>? {
        val parts = reference.removePrefix("wcore-art://").split('/')
        if (!reference.startsWith("wcore-art://") || parts.size != 3) return null
        val session = currentScope() ?: if (WCoreConnectionRepository.status.value == WCoreConnectionStatus.Connecting) {
            withTimeoutOrNull(15_000) {
                WCoreConnectionRepository.status.first { it != WCoreConnectionStatus.Connecting }
                currentScope()
            }
        } else null
        if (session == null) return null
        if (parts[0] != coreArtworkScope(session)) return null
        val needsInventory = synchronized(lock) { owner != session.owner || !inventory.containsKey(parts[1]) }
        if (needsInventory) refresh()
        val expired = synchronized(lock) {
            if (owner != session.owner || parts[0] != artScope) return null
            val item = inventory[parts[1]] ?: return null
            item.imageExpiresAt?.let { Clock.System.now() + 1.minutes >= it } ?: true
        }
        if (expired) refresh(force = true)
        return synchronized(lock) {
            if (owner != session.owner || currentScope() != session || parts[0] != artScope) return@synchronized null
            val item = inventory[parts[1]] ?: return@synchronized null
            if (item.imageExpiresAt?.let { Clock.System.now() >= it } != false) return@synchronized null
            val ticket = when (parts[2]) { "primary" -> item.posterTicket; "backdrop" -> item.backdropTicket; else -> null }
            ticket?.let { session to it }
        }
    }

    fun isOwnerCurrent(session: WCoreLibraryScope): Boolean = isOwnerCurrent(session.owner)

    fun isOwnerCurrent(session: WCoreLibraryOwner): Boolean =
        currentNuvioSyncIdentity() == session.account && ProfileRepository.activeProfileId == session.profileId &&
            WCoreConnectionRepository.currentOrigin() == session.origin &&
            WCoreConnectionRepository.connectionRevision() == session.revision

    fun isCurrent(session: WCoreLibraryScope): Boolean = currentScope() == session
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
