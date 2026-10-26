package com.nuvio.app.features.downloads

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.streams.StreamItem
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun hasEnglishDownloadAudioTracks(tracks: Tracks): Boolean =
    tracks.groups.any { group -> group.type == C.TRACK_TYPE_AUDIO &&
        (0 until group.length).any { index ->
            val format = group.getTrackFormat(index)
            isEnglishDownloadAudio(format.language, format.label) } }

/** Prepare silently to read container track metadata. Never start playback or retain a player. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal actual suspend fun confirmDownloadEnglishAudio(stream: StreamItem): Boolean {
    if (!stream.isDirectFileDownloadSource) return false
    val context = DownloadsPlatformDownloader.audioCheckContext() ?: return false
    val url = stream.playableDirectUrl ?: return false
    return withContext(Dispatchers.Main) {
        val budget = AtomicLong(8L * 1024 * 1024)
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(4_000).setReadTimeoutMs(4_000)
            .setDefaultRequestProperties(sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request))
        val bounded = DataSource.Factory {
            val upstream = http.createDataSource()
            object : DataSource by upstream {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    val remaining = budget.get()
                    if (remaining <= 0) throw IOException("Audio check byte limit reached")
                    val count = upstream.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                    if (count > 0) budget.addAndGet(-count.toLong())
                    return count
                }
            }
        }
        val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, bounded))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
        val player = ExoPlayer.Builder(context).setMediaSourceFactory(sourceFactory)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 1_000, 100, 100)
                .setTargetBufferBytes(512 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()).build()
        try {
            player.volume = 0f
            player.playWhenReady = false
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine<Boolean> { pending ->
                    player.addListener(object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            if (tracks.groups.isNotEmpty() && pending.isActive) pending.resume(hasEnglishDownloadAudioTracks(tracks))
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            if (pending.isActive) pending.resume(false)
                        }
                    })
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                }
            } ?: false
        } finally {
            player.release()
        }
    }
}
