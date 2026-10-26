package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable

/** Network policy must survive the Kotlin-to-Swift transfer boundary. */
@Serializable
internal data class IosBackgroundDownloadStart(
    val id: String, val url: String, val fileName: String,
    val headers: Map<String, String>, val wifiOnly: Boolean,
)
