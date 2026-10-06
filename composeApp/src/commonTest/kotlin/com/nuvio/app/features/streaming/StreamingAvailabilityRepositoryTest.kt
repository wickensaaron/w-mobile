package com.nuvio.app.features.streaming

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingAvailabilityRepositoryTest {
    private val show = """{
      "showType":"series", "title":"Example Series", "tmdbId":"tv/12345", "imdbId":"tt1234567",
      "releaseYear":2024,
      "imageSet":{
        "verticalPoster":{"w360":"https://images.example/poster.jpg"},
        "horizontalBackdrop":{"w1080":"https://images.example/backdrop.jpg"}
      }
    }"""

    @Test
    fun topShowsMapToPlayableMetadata() {
        val parsed = StreamingAvailabilityRepository.parseShows(Json.parseToJsonElement("[$show]"), wrapped = false)

        assertEquals(1, parsed.size)
        assertEquals("tt1234567", parsed.single().id)
        assertEquals("series", parsed.single().type)
        assertEquals("Example Series", parsed.single().name)
        assertEquals("https://images.example/poster.jpg", parsed.single().poster)
        assertEquals("https://images.example/backdrop.jpg", parsed.single().banner)
        assertEquals("2024", parsed.single().releaseInfo)
    }

    @Test
    fun catalogResultsUseShowsArrayAndSkipUnopenableItems() {
        val response = """{"shows":[$show,{"title":"No ID","showType":"movie"}]}"""
        val parsed = StreamingAvailabilityRepository.parseShows(Json.parseToJsonElement(response), wrapped = true)

        assertEquals(listOf("Example Series"), parsed.map { it.name })
    }

    @Test
    fun tmdbIdFallbackUsesNumericId() {
        val response = """[{"showType":"movie","title":"Example Film","tmdbId":"movie/238"}]"""
        val parsed = StreamingAvailabilityRepository.parseShows(Json.parseToJsonElement(response), wrapped = false)

        assertEquals("tmdb:238", parsed.single().id)
    }
}
