package com.nuvio.app.core.format

/** Formats a programme's absolute start or end time in the device time zone. */
expect fun formatLocalHourMinute(epochMs: Long): String
