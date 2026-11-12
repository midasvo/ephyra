package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.jdtech.jellyfin.core.presentation.dummy.dummyVideoMetadata
import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #510: the info chip on the badge row, and the media info it opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS")
class MediaInfoSheetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun stream(type: MediaStreamType, title: String, bitRate: Int? = null) =
        FindroidMediaStream(
            title = "",
            displayTitle = title,
            language = "eng",
            type = type,
            codec = "",
            profile = null,
            isExternal = false,
            path = null,
            channelLayout = null,
            videoRangeType = null,
            height = null,
            width = null,
            videoDoViTitle = null,
            bitRate = bitRate,
        )

    private fun source(
        name: String,
        container: String?,
        bitrate: Int?,
        vararg streams: FindroidMediaStream,
    ) =
        FindroidSource(
            id = name,
            name = name,
            type = FindroidSourceType.REMOTE,
            path = "",
            size = 0,
            mediaStreams = streams.toList(),
            container = container,
            bitrate = bitrate,
        )

    private val remux =
        source(
            "2160p Remux",
            "mkv",
            62_100_000,
            stream(MediaStreamType.VIDEO, "4K HEVC Dolby Vision", bitRate = 58_300_000),
            stream(MediaStreamType.AUDIO, "English - TrueHD 7.1"),
            stream(MediaStreamType.AUDIO, "English - Dolby Digital 5.1", bitRate = 640_000),
            stream(MediaStreamType.SUBTITLE, "English - PGSSUB"),
        )
    private val small =
        source("1080p", "mp4", 8_000_000, stream(MediaStreamType.VIDEO, "1080p H264 SDR"))

    @Test
    fun theBadgeRowWithoutAnInfoClickIsNotAButton() {
        rule.setContent { FindroidTheme { VideoMetadataBar(dummyVideoMetadata) } }

        rule.onAllNodesWithContentDescription("Media info").assertCountEquals(0)
        rule.onNodeWithTag(MEDIA_INFO_CHIP_TAG).assertDoesNotExist()
    }

    @Test
    fun theWholeBadgeRowIsOneButtonThatOpensTheMediaInfo() {
        var clicks = 0
        rule.setContent {
            FindroidTheme { VideoMetadataBar(dummyVideoMetadata, onInfoClick = { clicks++ }) }
        }

        rule
            .onNodeWithTag(MEDIA_INFO_CHIP_TAG)
            .assert(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun oneVersionShowsItsFileAndTracksAndNoPills() {
        rule.setContent { FindroidTheme { MediaInfoContent(listOf(remux)) } }

        rule.onNodeWithText("Media info").assertIsDisplayed()
        rule.onNodeWithTag(MEDIA_INFO_FILE_TAG).assertTextEquals("MKV · 62.1 Mbps")
        rule.onNodeWithText("4K HEVC Dolby Vision").assertIsDisplayed()
        rule.onNodeWithText("58.3 Mbps").assertIsDisplayed()
        // Further down, and so maybe below the fold of the test window.
        rule.onNodeWithText("English - Dolby Digital 5.1").assertExists()
        rule.onNodeWithText("640 kbps").assertExists()
        rule.onNodeWithText("English - PGSSUB").assertExists()
        // Two audio tracks: the heading says how many.
        rule.onNodeWithText("2").assertExists()
        rule.onNodeWithText("2160p Remux").assertDoesNotExist()
    }

    @Test
    fun pickingAnotherVersionShowsThatVersion() {
        rule.setContent { FindroidTheme { MediaInfoContent(listOf(remux, small)) } }

        rule.onNodeWithText("2160p Remux").assertIsSelected()
        rule.onNodeWithText("1080p").assertIsNotSelected()
        rule.onNodeWithTag(MEDIA_INFO_FILE_TAG).assertTextEquals("MKV · 62.1 Mbps")

        rule.onNodeWithText("1080p").performClick()

        rule.onNodeWithText("1080p").assertIsSelected()
        rule.onNodeWithTag(MEDIA_INFO_FILE_TAG).assertTextEquals("MP4 · 8.0 Mbps")
        rule.onNodeWithText("1080p H264 SDR").assertIsDisplayed()
        rule.onNodeWithText("4K HEVC Dolby Vision").assertDoesNotExist()
    }

    @Test
    fun aShorterListAfterAReloadFallsBackToAVersionThatExists() {
        var sources by mutableStateOf(listOf(remux, small))
        rule.setContent { FindroidTheme { MediaInfoContent(sources) } }
        rule.onNodeWithText("1080p").performClick()

        sources = listOf(remux)
        rule.waitForIdle()

        rule.onNodeWithTag(MEDIA_INFO_FILE_TAG).assertTextEquals("MKV · 62.1 Mbps")
        rule.onNodeWithText("4K HEVC Dolby Vision").assertIsDisplayed()
    }

    @Test
    fun aVersionWithoutANameGetsItsNumber() {
        val unnamed = source(" ", "avi", null, stream(MediaStreamType.VIDEO, "480p MPEG4"))
        rule.setContent { FindroidTheme { MediaInfoContent(listOf(small, unnamed)) } }

        rule.onNodeWithText("2").assertIsNotSelected().performClick()

        rule.onNodeWithText("2").assertIsSelected()
        rule.onNodeWithTag(MEDIA_INFO_FILE_TAG).assertTextEquals("AVI")
    }

    @Test
    fun aDownloadWithNothingButTracksShowsNoFileLine() {
        val download = source("local", null, null, stream(MediaStreamType.VIDEO, "1080p H264"))
        rule.setContent { FindroidTheme { MediaInfoContent(listOf(download)) } }

        rule.onNodeWithTag(MEDIA_INFO_FILE_TAG).assertDoesNotExist()
        rule.onNodeWithText("1080p H264").assertIsDisplayed()
    }

    @Test
    fun noSourcesShowsNothing() {
        rule.setContent { FindroidTheme { MediaInfoContent(emptyList()) } }

        rule.onNodeWithText("Media info").assertDoesNotExist()
    }
}
