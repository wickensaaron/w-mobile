package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvNavigationVisibilityTest {
    @Test
    fun navigationRemainsAvailableBeforeAProviderIsConfigured() {
        assertFalse(LiveTvUiState().hasPlaylist)
        assertTrue(LiveTvUiState().showInNavigation)
    }

    @Test
    fun explicitNavigationPreferenceStillHidesLiveTv() {
        assertFalse(LiveTvUiState(isNavigationEnabled = false).showInNavigation)
    }
}
