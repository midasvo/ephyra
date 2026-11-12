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
 * #617: a subtitle that arrives late must not undo the track selection. When sub-add runs, mpv
 * re-evaluates automatic track selection and would otherwise switch to the newly loaded subtitle,
 * overriding the user's choice or the forced subtitle of #597.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MpvSubtitleSelectionTest {

    private fun group(type: String, id: String) =
        TrackGroup(Format.Builder().setId(id).setSampleMimeType(type).build())

    @Test
    fun `automatic selection with no subtitle selected lets mpv choose`() {
        assertNull(targetSubtitleTrack(TrackSelectionParameters.DEFAULT, null))
        assertNull(targetSubtitleTrack(TrackSelectionParameters.DEFAULT, "no"))
        assertNull(targetSubtitleTrack(TrackSelectionParameters.DEFAULT, "auto"))
    }

    @Test
    fun `an already selected subtitle track is preserved`() {
        // #597: a forced subtitle added before external ones, or an embedded track inside the file.
        assertEquals("1", targetSubtitleTrack(TrackSelectionParameters.DEFAULT, "1"))
        assertEquals("2", targetSubtitleTrack(TrackSelectionParameters.DEFAULT, "2"))
    }

    @Test
    fun `disabled subtitles stay disabled`() {
        val parameters =
            TrackSelectionParameters.DEFAULT.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()

        assertEquals("no", targetSubtitleTrack(parameters, null))
        assertEquals("no", targetSubtitleTrack(parameters, "no"))
        assertEquals("no", targetSubtitleTrack(parameters, "1"))
    }

    @Test
    fun `an explicit subtitle override is preserved`() {
        val parameters =
            TrackSelectionParameters.DEFAULT.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group(MimeTypes.TEXT_VTT, "3"), 0))
                .build()

        assertEquals("3", targetSubtitleTrack(parameters, null))
        assertEquals("3", targetSubtitleTrack(parameters, "no"))
        assertEquals("3", targetSubtitleTrack(parameters, "1"))
    }

    @Test
    fun `overrides of other track types do not touch subtitles`() {
        val parameters =
            TrackSelectionParameters.DEFAULT.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group(MimeTypes.AUDIO_AAC, "2"), 0))
                .build()

        assertNull(targetSubtitleTrack(parameters, "no"))
        assertEquals("1", targetSubtitleTrack(parameters, "1"))
    }
}
