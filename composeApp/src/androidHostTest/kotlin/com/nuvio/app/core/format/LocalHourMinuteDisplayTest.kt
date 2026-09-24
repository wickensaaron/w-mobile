package com.nuvio.app.core.format

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class LocalHourMinuteDisplayTest {
    @Test
    fun guideTimeUsesDeviceZoneAcrossBritishSummerTime() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            val summerEpoch = Instant.parse("2026-09-24T18:00:00Z").toEpochMilliseconds()
            val winterEpoch = Instant.parse("2026-12-24T18:00:00Z").toEpochMilliseconds()
            assertEquals("19:00", formatLocalHourMinute(summerEpoch))
            assertEquals("18:00", formatLocalHourMinute(winterEpoch))
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
