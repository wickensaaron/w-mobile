package com.nuvio.app

import androidx.navigation3.runtime.NavKey
import com.nuvio.app.navigation.DownloadsLibraryRoute
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsNavigationSavedStateTest {
    @Test
    fun downloadsLibraryRouteSurvivesNavigationSerialization() {
        val route: NavKey = DownloadsLibraryRoute(title = "Downloads", showActive = true)
        val serializer = PolymorphicSerializer(NavKey::class)
        val json = Json {
            serializersModule = navigationSavedStateConfiguration.serializersModule
        }

        val saved = json.encodeToString(serializer, route)
        val restored = json.decodeFromString(serializer, saved)

        assertEquals(route, restored)
    }
}
