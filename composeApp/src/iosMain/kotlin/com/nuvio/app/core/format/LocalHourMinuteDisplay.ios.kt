package com.nuvio.app.core.format

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

actual fun formatLocalHourMinute(epochMs: Long): String = NSDateFormatter().apply {
    dateFormat = "HH:mm"
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1_000.0))
