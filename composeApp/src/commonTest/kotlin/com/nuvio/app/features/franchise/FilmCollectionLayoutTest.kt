package com.nuvio.app.features.franchise

import kotlin.test.Test
import kotlin.test.assertEquals

class FilmCollectionLayoutTest {
    @Test fun phoneWidthsKeepReadableCards() {
        assertEquals(1, filmCollectionColumnCount(320f, 1f))
        assertEquals(2, filmCollectionColumnCount(375f, 1f))
        assertEquals(2, filmCollectionColumnCount(390f, 1f))
    }
    @Test fun accessibilityTextReducesColumnsAndLandscapeIsBounded() {
        assertEquals(1, filmCollectionColumnCount(390f, 1.5f))
        assertEquals(3, filmCollectionColumnCount(844f, 1f))
        assertEquals(1, filmCollectionColumnCount(200f, 2f))
    }
}
