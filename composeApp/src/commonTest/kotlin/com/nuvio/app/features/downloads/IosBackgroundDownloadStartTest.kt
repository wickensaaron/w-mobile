package com.nuvio.app.features.downloads

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class IosBackgroundDownloadStartTest {
    @Test fun bothNetworkPoliciesAreExplicitInTheNativeRequestPayload() {
        for (wifiOnly in listOf(true, false)) {
            val request = IosBackgroundDownloadStart("id", "https://example.invalid/file", "file.mkv", emptyMap(), wifiOnly)
            val payload = Json.encodeToString(request)
            assertEquals(wifiOnly, Json.parseToJsonElement(payload).jsonObject.getValue("wifiOnly").jsonPrimitive.boolean)
            assertEquals(request, Json.decodeFromString<IosBackgroundDownloadStart>(payload))
        }
    }
}
