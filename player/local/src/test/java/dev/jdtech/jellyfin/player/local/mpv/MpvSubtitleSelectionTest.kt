package dev.jdtech.jellyfin.player.local.mpv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #617: a subtitle that arrives late must not undo the user's explicit track selection. When sub-add
 * runs, mpv re-evaluates automatic track selection and would otherwise switch to the newly loaded
 * subtitle, overriding the user's choice or disabled state. Automatic selection remains unforced so
 * mpv can select the best language match across all added subtitles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MpvSubtitleSelectionTest {

    private fun group(type: String, id: String) =
        TrackGroup(Format.Builder().setId(id).setSampleMimeType(type).build())

    @Test
    fun `automatic selection lets mpv choose without forcing a track`() {
        assertNull(targetSubtitleTrack(TrackSelectionParameters.DEFAULT))
    }

    @Test
    fun `disabled subtitles return no`() {
        val parameters =
            TrackSelectionParameters.DEFAULT.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()

        assertEquals("no", targetSubtitleTrack(parameters))
    }

    @Test
    fun `explicit subtitle override returns format id`() {
        val parameters =
            TrackSelectionParameters.DEFAULT.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group(MimeTypes.TEXT_VTT, "3"), 0))
                .build()

        assertEquals("3", targetSubtitleTrack(parameters))
    }

    @Test
    fun `multiple text overrides return the last override matching setTrackSelectionParameters`() {
        val parameters =
            TrackSelectionParameters.DEFAULT.buildUpon()
                .addOverride(TrackSelectionOverride(group(MimeTypes.TEXT_VTT, "3"), 0))
                .addOverride(TrackSelectionOverride(group(MimeTypes.TEXT_VTT, "7"), 0))
                .build()

        assertEquals("7", targetSubtitleTrack(parameters))
    }

    @Test
    fun `overrides of other track types do not touch subtitles`() {
        val parameters =
            TrackSelectionParameters.DEFAULT.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group(MimeTypes.AUDIO_AAC, "2"), 0))
                .build()

        assertNull(targetSubtitleTrack(parameters))
    }
}
