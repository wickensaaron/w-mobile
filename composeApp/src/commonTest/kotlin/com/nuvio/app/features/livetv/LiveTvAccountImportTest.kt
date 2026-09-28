package com.nuvio.app.features.livetv

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvAccountImportTest {
    private val sourceId = "11111111-1111-4111-8111-111111111111"
    private val owner = LiveTvAccountScope("https://account.example.invalid", "account-a", 1)
    private val source = ImportedLiveTvSource(sourceId, "Source", "XTREAM", "https://provider.example.invalid", "test-user", "test-password")

    @Test
    fun `multiple imported sources retain exact cloud identities and order`() {
        val snapshot = decodeImportedLiveTvSources("""{"revision":12,"providers":[
            {"id":"22222222-2222-4222-8222-222222222222","name":"Playlist","type":"M3U","endpoint":"https://playlist.example.invalid/list.m3u","order":2},
            {"id":"$sourceId","name":"Xtream","type":"XTREAM","endpoint":"https://provider.example.invalid","username":"test-user","password":"test-password","order":1}
        ]}""")
        assertEquals(12L, snapshot.revision)
        assertEquals(listOf(sourceId, "22222222-2222-4222-8222-222222222222"), snapshot.providers.map { it.id })
        assertFalse(snapshot.toString().contains("test-password"))
        assertFalse(snapshot.providers.first().summary().toString().contains("test-user"))
    }

    @Test
    fun `invalid source identities and non-web endpoints fail closed`() {
        listOf("not-a-source-uuid" to "https://provider.example.invalid", sourceId to "file:///private/source").forEach { (id, endpoint) ->
            assertFailsWith<IllegalArgumentException> {
                decodeImportedLiveTvSources("""{"providers":[{"id":"$id","name":"Source","type":"M3U","endpoint":"$endpoint"}]}""")
            }
        }
        val repeated = """{"id":"$sourceId","name":"Source","type":"M3U","endpoint":"https://provider.example.invalid"}"""
        assertFailsWith<IllegalArgumentException> { decodeImportedLiveTvSources("{\"providers\":[$repeated,$repeated]}") }
    }

    @Test
    fun `decoder accepts actual server field limits and rejects an invalid source order`() {
        val valid = """{"providers":[{"id":"$sourceId","name":"${"N".repeat(160)}","type":"XTREAM",
            "endpoint":"https://provider.example.invalid","username":"${"U".repeat(512)}",
            "password":"${"P".repeat(4096)}","enabled":true,"order":99}]}"""
        val accepted = decodeImportedLiveTvSources(valid).providers.single()
        assertEquals(160, accepted.name.length)
        assertEquals(512, accepted.username.length)
        assertEquals(4096, accepted.password.length)
        assertFailsWith<IllegalArgumentException> { decodeImportedLiveTvSources(valid.replace("\"order\":99", "\"order\":100")) }
    }

    @Test
    fun `source ownership rejects signout account profile backend and catalogue changes`() {
        val channel = LiveTvChannel("$sourceId:20180", "BBC Two", "https://stream.example.invalid/20180",
            playlistId = sourceId, accountScope = owner, accountSourceGeneration = 7)
        assertTrue(ownsImportedLiveTvChannel(channel, owner, owner, 7, listOf(source)))
        listOf(null, owner.copy(account = "account-b"), owner.copy(profile = 2), owner.copy(backend = "https://other.example.invalid"))
            .forEach { changed -> assertFalse(ownsImportedLiveTvChannel(channel, owner, changed, 7, listOf(source))) }
        assertFalse(ownsImportedLiveTvChannel(channel, owner, owner, 8, listOf(source)))
        assertFalse(ownsImportedLiveTvChannel(channel, owner, owner, 7, listOf(source.copy(enabled = false))))
        assertFalse(ownsImportedLiveTvChannel(channel, owner, owner, 7, emptyList()))
        assertFalse(ownsImportedLiveTvChannel(channel.copy(id = "20180"), owner, owner, 7, listOf(source)))
    }

    @Test
    fun `imported named M3U uses provider UUID indices while local playlist IDs remain local`() {
        val payload = """#EXTM3U
            #EXTINF:-1 tvg-id="bbc1",BBC One
            https://stream.example.invalid/one
            #EXTINF:-1 tvg-id="bbc2",BBC Two
            https://stream.example.invalid/two
        """.trimIndent()
        val playlist = LiveTvPlaylist(sourceId, "Playlist", LiveTvPlaylistType.Url, "https://playlist.example.invalid/list.m3u")
        val imported = parseImportedLiveTvPlaylist(payload, source.copy(type = "M3U"))
        val local = parseM3uPlaylist(payload, playlist)
        assertEquals(listOf("$sourceId:0", "$sourceId:1"), imported.map { it.id })
        assertTrue(local.all { it.id.startsWith("live:") })
        assertEquals(local.map { it.streamUrl }, imported.map { it.streamUrl })
    }

    @Test
    fun `imported M3U retains duplicate identities quoted commas and explicit groups`() {
        val payload = """#EXTM3U
            #EXTINF:-1 tvg-name="Name, with comma" group-title="Original",Channel One
            #EXTGRP:Override
            https://stream.example.invalid/same
            #EXTINF:-1 tvg-name="Channel Two",Channel Two
            https://stream.example.invalid/same
            #EXTINF:-1,Channel Three
            https://stream.example.invalid/three
        """.trimIndent()
        val imported = parseImportedLiveTvPlaylist(payload, source.copy(type = "M3U"))
        assertEquals(listOf("$sourceId:0", "$sourceId:1", "$sourceId:2"), imported.map { it.id })
        assertEquals(listOf("Channel One", "Channel Two", "Channel Three"), imported.map { it.name })
        assertEquals("Override", imported.first().group)
        assertEquals(imported[0].streamUrl, imported[1].streamUrl)
    }

    @Test
    fun `ambiguous media rows cannot shift imported identities or inherit previous metadata`() {
        listOf("""#EXTM3U
            #EXTINF:-1,Previous name
            udp://unsupported.example.invalid/one
            https://stream.example.invalid/two
        """, """#EXTM3U
            https://stream.example.invalid/unnamed
            #EXTINF:-1,Named
            https://stream.example.invalid/named
        """).forEach { payload ->
            assertFailsWith<IllegalArgumentException> {
                parseImportedLiveTvPlaylist(payload.trimIndent(), source.copy(type = "M3U"))
            }
        }
    }

    @Test
    fun `an imported channel never borrows another source or local guide rows`() {
        val imported = LiveTvChannel("$sourceId:20180", "BBC Two", "https://stream.example.invalid/20180",
            playlistId = sourceId, guideId = "bbc2", accountScope = owner, accountSourceGeneration = 7)
        val row = LiveTvProgramme(imported.id, "Programme", startEpochMs = 1, stopEpochMs = 2)
        val globalOnly = LiveTvUiState(programmes = mapOf("bbc2" to listOf(row)))
        assertTrue(globalOnly.programmesFor(imported).isEmpty())
        assertEquals(listOf(row), globalOnly.programmesFor(imported.copy(accountScope = null)))
        assertEquals(listOf(row), globalOnly.copy(programmes = mapOf(imported.id to listOf(row))).programmesFor(imported))
    }

    @Test
    fun `large imported playlists check cancellation while scanning rather than finishing every line`() {
        var checks = 0
        val payload = "#EXTM3U\n" + "# comment\n".repeat(1_000)
        assertFailsWith<CancellationException> {
            parseImportedLiveTvPlaylist(payload, source.copy(type = "M3U"), checkActive = {
                checks++
                if (checks == 3) throw CancellationException("Cancelled")
            })
        }
        assertEquals(3, checks)
    }
}
