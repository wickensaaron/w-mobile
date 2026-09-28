package com.nuvio.app.features.livetv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvAccountGuideSaveTest {
    private val source = "11111111-1111-4111-8111-111111111111"
    private val id = "$source:1"
    private val backup = "$source:2"
    private val unrelated = "$source:3"
    private val now = 1_800_000_000_000L
    private val owner = LiveTvAccountScope("https://example.supabase.co", "account", 1)

    @Test fun defaultSaveContainsEveryFieldRequiredByTheExistingServerContract() {
        val body = Json.parseToJsonElement(LiveTvAccountGuideSync.encodeSaveBody(1, 0,
            LiveTvAccountGuidePreferences(favouriteIds = listOf(id)))) as JsonObject
        val preferences = body.getValue("p_preferences") as JsonObject
        assertEquals(setOf("version", "hiddenGroupKeys", "orderedGroupKeys", "favouriteIds",
            "autoTidy", "ukOnly", "collapseRegional"), preferences.keys)
        assertEquals("true", preferences.getValue("ukOnly").jsonPrimitive.content)
        assertTrue(preferences.getValue("hiddenGroupKeys").jsonArray.isEmpty())
        assertEquals(id, preferences.getValue("favouriteIds").jsonArray.single().jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { LiveTvAccountGuideSync.encodeSaveBody(0, 0, LiveTvAccountGuidePreferences()) }
        assertFailsWith<IllegalArgumentException> { LiveTvAccountGuideSync.encodeSaveBody(1, Long.MAX_VALUE, LiveTvAccountGuidePreferences()) }
    }

    @Test fun conflictIntentPreservesNewerCategoryAndOtherFavouriteChoices() {
        val key = "a".repeat(64)
        val latest = LiveTvAccountGuidePreferences(hiddenGroupKeys = listOf(key), orderedGroupKeys = listOf(key),
            favouriteIds = listOf(backup, unrelated), ukOnly = false, collapseRegional = false)
        val updated = applyLiveTvAccountFavouriteIntent(LiveTvAccountGuideSnapshot(revision = 8, preferences = latest),
            id, setOf(id, backup), shouldAdd = true)
        assertEquals(latest.copy(favouriteIds = listOf(unrelated, id)), updated)
        assertEquals(listOf(unrelated), applyLiveTvAccountFavouriteIntent(LiveTvAccountGuideSnapshot(preferences = updated),
            id, setOf(id, backup), shouldAdd = false).favouriteIds)
    }

    @Test fun savedAlternativeOnlyStarsTheBroadcastWithItsOwnCurrentProgrammeData() {
        fun channel(channelId: String) = LiveTvChannel(channelId, "BBC One", "https://example.invalid/live",
            playlistId = source, accountScope = owner, accountSourceGeneration = 3)
        val primary = channel(id)
        val alternative = channel(backup)
        val row = LiveTvAccountGuideRow(primary, "BBC One", "All UK", 101, null, listOf(primary, alternative))
        val programme = LiveTvProgramme(backup, "News", startEpochMs = now - 1000, stopEpochMs = now + 1000)
        assertFalse(isLiveTvGuideRowFavourite(row, setOf(backup), emptyMap(), now))
        assertTrue(isLiveTvGuideRowFavourite(row, setOf(backup), mapOf(backup to listOf(programme)), now))
        assertFalse(isLiveTvGuideRowFavourite(row, setOf(backup), mapOf(backup to listOf(programme.copy(channelId = id))), now))
        assertFalse(isLiveTvGuideRowFavourite(row, setOf(backup), mapOf(backup to listOf(programme.copy(stopEpochMs = now))), now))
        assertFalse(isLiveTvGuideRowFavourite(row.copy(alternatives = listOf(alternative.copy(accountSourceGeneration = 2))),
            setOf(backup), mapOf(backup to listOf(programme)), now))
    }
}
