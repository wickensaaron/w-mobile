package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerNextEpisodeRulesTest {
    private fun shouldShow(positionMs: Long, intervals: List<SkipInterval>) =
        PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = positionMs,
            durationMs = 1_200_000L,
            skipIntervals = intervals,
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 97f,
            thresholdMinutesBeforeEnd = 2f,
        )

    @Test
    fun unmarkedGapAfterOutroUsesConfiguredThreshold() {
        val intervals = listOf(SkipInterval(1_140.0, 1_160.0, "outro", "introdb"))
        assertFalse(shouldShow(1_163_000L, intervals))
        assertTrue(shouldShow(1_165_000L, intervals))
    }

    @Test
    fun explicitPostCreditsSceneDelaysPromptUntilSceneEnds() {
        val intervals = listOf(
            SkipInterval(1_140.0, 1_160.0, "outro", "introdb"),
            SkipInterval(1_162.0, 1_185.0, "post-credits", "introdb"),
        )
        assertFalse(shouldShow(1_180_000L, intervals))
        assertTrue(shouldShow(1_185_000L, intervals))
    }
}
