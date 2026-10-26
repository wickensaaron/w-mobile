package com.nuvio.app.features.downloads

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.google.common.collect.ImmutableList
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class DownloadAudioCheckTest {
    private fun group(mime: String, language: String?, support: Int = C.FORMAT_HANDLED) = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(mime).setLanguage(language).build()),
        false, intArrayOf(support), booleanArrayOf(false),
    )

    @Test fun englishSubtitlesNeverCountAsEnglishAudio() {
        assertFalse(hasEnglishDownloadAudioTracks(Tracks(ImmutableList.of(
            group(MimeTypes.AUDIO_AAC, "fra"), group(MimeTypes.APPLICATION_SUBRIP, "eng"),
        ))))
        assertFalse(hasEnglishDownloadAudioTracks(Tracks(ImmutableList.of(group(MimeTypes.AUDIO_AAC, null)))))
        assertTrue(hasEnglishDownloadAudioTracks(Tracks(ImmutableList.of(
            group(MimeTypes.AUDIO_AAC, "fra"), group(MimeTypes.AUDIO_AAC, "eng"),
        ))))
    }

    @Test fun containerLanguageIsIndependentOfTheCheckersDecoderSupport() {
        assertTrue(hasEnglishDownloadAudioTracks(Tracks(ImmutableList.of(
            group(MimeTypes.AUDIO_AAC, "eng", C.FORMAT_UNSUPPORTED_TYPE),
        ))))
    }
}
