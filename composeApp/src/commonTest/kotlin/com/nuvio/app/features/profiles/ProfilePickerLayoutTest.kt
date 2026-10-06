package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfilePickerLayoutTest {
    @Test fun phoneCardsFitWidthsIncludingSmallPhones() {
        listOf(280f, 320f, 360f, 375f, 390f, 430f).forEach { viewport ->
            val available = viewport - 48f
            val layout = profilePickerLayout(available, 1f)
            val rowWidth = layout.cardWidthDp * layout.columns + 20f * (layout.columns - 1)
            assertTrue(rowWidth <= available, "Cards overflow $viewport")
            assertTrue(layout.cardWidthDp >= 126f)
        }
    }

    @Test fun narrowAndLargeTextLayoutsUseOneColumn() {
        assertEquals(1, profilePickerLayout(260f, 1f).columns)
        assertEquals(1, profilePickerLayout(342f, 1.4f).columns)
        assertEquals(2, profilePickerLayout(272f, 1f).columns)
        assertEquals(126f, profilePickerLayout(272f, 1f).cardWidthDp)
    }

    @Test fun wideNormalTextCardsKeepExistingSize() {
        assertEquals(ProfilePickerLayout(2, 150f), profilePickerLayout(500f, 1f))
    }
}
