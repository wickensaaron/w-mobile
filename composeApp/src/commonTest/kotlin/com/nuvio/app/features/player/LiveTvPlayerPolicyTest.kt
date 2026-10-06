package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTvPlayerPolicyTest {
    @Test fun archiveAndRecordingsUseOwnedPlayback() {
        assertTrue(isManagedLiveTvPlayback("live-tv-catchup"))
        assertTrue(isManagedLiveTvPlayback("live-tv-recording"))
        assertFalse(isManagedLiveTvPlayback(null))
        assertFalse(isManagedLiveTvPlayback("ordinary-addon"))
    }

    @Test fun skipOnlyTargetsRecordedProgrammeInsideKnownDuration() {
        assertEquals(120_000L, recordingStartSkipTargetMs(120_000, 20_000, 600_000))
        assertNull(recordingStartSkipTargetMs(120_000, 120_000, 600_000))
        assertNull(recordingStartSkipTargetMs(120_000, 0, 60_000))
        assertNull(recordingStartSkipTargetMs(120_000, 0, 0))
        assertNull(recordingStartSkipTargetMs(-1, 0, 600_000))
        assertNull(recordingStartSkipTargetMs(null, 0, 600_000))
    }
}
