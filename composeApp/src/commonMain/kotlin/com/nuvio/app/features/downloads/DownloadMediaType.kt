package com.nuvio.app.features.downloads

/** Reject successful HTML/JSON login and error pages before touching a partial file. */
internal fun isDownloadMediaContentType(value: String?): Boolean {
    val type = value?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return type.isEmpty() || type.startsWith("video/") || type.startsWith("audio/") ||
        type in setOf("application/octet-stream", "application/mp4", "application/x-matroska",
            "application/ogg", "application/vnd.ms-asf", "application/mp2t",
            "application/x-mpeg-ts", "application/x-msvideo", "application/x-flv")
}
