package dev.jdtech.jellyfin.player.local.mpv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #460: MPVPlayer deselects video while it has no surface and selects it again from the track
 * selection parameters when the surface comes back. That must give back exactly what was
 * selected, so background audio does not turn video on and an explicit pick is kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MpvVideoSelectionTest {

    private fun group(type: String, id: String) =
        TrackGroup(Format.Builder().setId(id).setSampleMimeType(type).build())

    @Test
    fun `default parameters select video automatically`() {
        assertEquals("auto", videoTrackSelection(TrackSelectionParameters.DEFAULT))
    }

    @Test
    fun `disabled video stays deselected`() {
        // PlayerViewModel.setVideoEnabled(false), from onStop with background audio.
        val parameters =
            TrackSelectionParameters.DEFAULT.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()

        assertEquals("no", videoTrackSelection(parameters))
    }

    @Test
    fun `an explicit video track is selected again`() {
        val parameters =
            TrackSelectionParameters.DEFAULT.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group(MimeTypes.VIDEO_H264, "2"), 0))
                .build()

        assertEquals("2", videoTrackSelection(parameters))
    }

    @Test
    fun `overrides of other track types do not touch video`() {
        val parameters =
            TrackSelectionParameters.DEFAULT.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group(MimeTypes.AUDIO_AAC, "3"), 0))
                .setOverrideForType(TrackSelectionOverride(group(MimeTypes.TEXT_VTT, "4"), 0))
                .build()

        assertEquals("auto", videoTrackSelection(parameters))
    }
}
