package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ForYouRankingTest {
    private fun item(id: String, type: String = "movie") = MetaPreview(id, type, id, poster = "poster.jpg")
    private fun batch(seed: String, vararg ids: String) = ForYouBatch(ForYouSeed(seed, "movie", seed, 1.0), ids.map { item(it) })

    @Test fun agreementAcrossSeedsOutranksSingleFirstResult() {
        val ranked = rankForYou(listOf(batch("a", "single", "shared"), batch("b", "other", "shared")), emptySet())
        assertEquals("shared", ranked.first().item.id)
        assertEquals(listOf("a", "b"), ranked.first().reasons)
    }

    @Test fun duplicateSeedsAndResultsCannotInflateScores() {
        val ranked = rankForYou(listOf(batch("a", "one", "one"), batch("a", "one")), emptySet())
        assertEquals(1, ranked.size)
        assertEquals(2.0, ranked.single().score)
    }

    @Test fun exclusionsRespectTypeAliasesAndKeepMovieAndSeriesDistinct() {
        val batch = ForYouBatch(ForYouSeed("seed", "series", "Seed", 1.0), listOf(item("tmdb:1", "tv"), item("tmdb:1", "movie")))
        val ranked = rankForYou(listOf(batch), setOf(forYouKey("series", "tmdb:1")))
        assertEquals("movie", ranked.single().item.type)
    }

    @Test fun filtersMissingArtAndUnsupportedMedia() {
        val batch = ForYouBatch(ForYouSeed("seed", "movie", "Seed", 1.0), listOf(item("a").copy(poster = null), item("b", "channel")))
        assertTrue(rankForYou(listOf(batch), emptySet()).isEmpty())
    }

    @Test fun mixesSeedSourcesAndHonoursLimit() {
        val ranked = rankForYou(listOf(batch("a", "a1", "a2", "a3", "a4"), batch("b", "b1")), emptySet(), 4)
        assertEquals(4, ranked.size)
        assertTrue(ranked.take(3).any { it.item.id == "b1" })
    }

    @Test fun feedbackSeparatesAccountsAndProfiles() {
        assertNotEquals(forYouFeedbackKey("alice", 1), forYouFeedbackKey("alice", 2))
        assertNotEquals(forYouFeedbackKey("alice", 1), forYouFeedbackKey("bob", 1))
        assertNotEquals(forYouFeedbackKey(null, 1), forYouFeedbackKey("alice", 1))
    }

    @Test fun shortlistAllowsTwentyFourVisibleAndRefillsHiddenPicks() {
        val ranked = rankForYou(listOf(batch("seed", *(1..60).map { "tmdb:$it" }.toTypedArray())), emptySet(), 48)
        assertEquals(48, ranked.size)
        val original = visibleForYou(ranked, emptySet())
        assertEquals(24, original.size)
        val hidden = forYouKey(original.first().item.type, original.first().item.id)
        val afterHide = visibleForYou(ranked, setOf(hidden))
        assertEquals(24, afterHide.size)
        assertTrue(afterHide.none { forYouKey(it.item.type, it.item.id) == hidden })
        assertEquals(original, visibleForYou(ranked, emptySet()))
    }
}
