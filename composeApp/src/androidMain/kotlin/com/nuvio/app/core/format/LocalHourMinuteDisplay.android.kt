package com.nuvio.app.core.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hourMinuteFormatter = DateTimeFormatter.ofPattern("HH:mm")

actual fun formatLocalHourMinute(epochMs: Long): String =
    hourMinuteFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
