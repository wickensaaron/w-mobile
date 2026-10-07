package com.nuvio.app

import androidx.navigation3.runtime.NavKey
import com.nuvio.app.navigation.FilmCollectionsBrowseRoute
import com.nuvio.app.navigation.FilmFranchiseRoute
import com.nuvio.app.navigation.StreamingServiceRoute
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingNavigationSavedStateTest {
    @Test
    fun streamingRoutesSurviveNavigationSerialization() {
        val routes: List<NavKey> = listOf(
            StreamingServiceRoute(serviceId = "8"),
            FilmFranchiseRoute(collectionId = 86311, title = "The Avengers"),
            FilmCollectionsBrowseRoute,
        )
        val serializer = PolymorphicSerializer(NavKey::class)
        val json = Json {
            serializersModule = navigationSavedStateConfiguration.serializersModule
        }

        routes.forEach { route ->
            val saved = json.encodeToString(serializer, route)
            assertEquals(route, json.decodeFromString(serializer, saved))
        }
    }
}
