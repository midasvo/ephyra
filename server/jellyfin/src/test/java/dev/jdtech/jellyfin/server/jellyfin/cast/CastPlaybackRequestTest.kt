package dev.jdtech.jellyfin.server.jellyfin.cast

import android.app.Application
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.PlaybackRequest
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.repository.StandInServer
import dev.jdtech.jellyfin.server.jellyfin.JellyfinDeviceProfiles
import dev.jdtech.jellyfin.server.jellyfin.JellyfinMediaServer
import dev.jdtech.jellyfin.server.jellyfin.toUuid
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #767: pins the playback info request of a Cast session and the URLs the receiver gets from the
 * answer, at the HTTP boundary. The expected bodies (`resources/playback/cast-request-*.json`) were
 * recorded from the Cast controller's own request before it moved to the port, and are compared
 * byte for byte: profile, bitrate, flags and stream indexes. The URLs are those the controller
 * built itself before: the transcoding URL behind the server's address, else the static file with
 * the token, and the subtitles that are on the server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CastPlaybackRequestTest {
    private val itemId = "8f086228-6a92-020d-0cb7-35d734765afe".toItemId()
    private var answer = "playback/cast-direct.json"
    private val server = StandInServer { request ->
        if (request.path.endsWith("/PlaybackInfo")) StandInServer.resource(answer) else null
    }
    private val playback by lazy {
        val context = StandInServer.context()
        val api =
            JellyfinApi(context).apply {
                api.update(baseUrl = server.baseUrl, accessToken = "token")
                userId = StandInServer.USER_ID.toUuid()
            }
        JellyfinMediaServer(api, JellyfinDeviceProfiles()).playback
    }

    @After
    fun stop() {
        server.close()
    }

    private fun sentBody(): String {
        val request = server.requests.single { it.path.endsWith("/PlaybackInfo") }
        assertEquals(
            "POST /Items/${itemId.asString()}/PlaybackInfo",
            "${request.method} ${request.path}",
        )
        return request.body
    }

    /** What the Cast controller reads of a source. */
    private data class Seen(
        val path: String,
        val transcoded: Boolean,
        val playSessionId: String?,
        val defaultAudioStreamIndex: Int?,
        val defaultSubtitleStreamIndex: Int?,
        val streams: List<String>,
    )

    private fun ItemSource.seen() =
        Seen(
            path = path,
            transcoded = transcoded,
            playSessionId = playSessionId,
            defaultAudioStreamIndex = defaultAudioStreamIndex,
            defaultSubtitleStreamIndex = defaultSubtitleStreamIndex,
            streams =
                mediaStreams
                    .filter { it.type != MediaStreamType.VIDEO }
                    .map {
                        "${it.type} ${it.index} ${it.title.ifEmpty { it.displayTitle }} " +
                            "${it.language} ${it.codec} external=${it.isExternal} " +
                            "forced=${it.isForced} hi=${it.isHearingImpaired} " +
                            "default=${it.isDefault} ${it.channelLayout} ${it.path}"
                    },
        )

    private val subtitles
        get() =
            listOf(
                "SUBTITLE 3 English - SUBRIP - External eng subrip external=true forced=false " +
                    "hi=true default=false null ${server.baseUrl}/Videos/${itemId.asString()}/$SOURCE_ID" +
                    "/Subtitles/3/0/Stream.vtt?ApiKey=token",
                "SUBTITLE 4 Signs nld PGSSUB external=false forced=true hi=false default=false " +
                    "null null",
                // Not on the server: the receiver is not given it.
                "SUBTITLE 5 German - SUBRIP - External deu subrip external=true forced=false " +
                    "hi=false default=false null null",
            )

    private val audio =
        listOf(
            "AUDIO 1 English 5.1 eng aac external=false forced=false hi=false default=true 5.1 null",
            "AUDIO 2 Dutch - AC3 - Stereo nld ac3 external=false forced=false hi=false " +
                "default=false stereo null",
        )

    @Test
    fun `a receiver without HEVC, uncapped, with the file's own audio`() = runBlocking {
        val sources =
            playback.sources(
                PlaybackRequest(
                    itemId = itemId,
                    target = PlaybackTarget.CastReceiver(supportsHevc = false),
                    mediaSourceId = SOURCE_ID.toMediaSourceId(),
                )
            )

        assertEquals(StandInServer.resource("playback/cast-request-h264.json"), sentBody())
        assertEquals(
            Seen(
                path =
                    "${server.baseUrl}/Videos/${itemId.asString()}/stream?static=true" +
                        "&mediaSourceId=$SOURCE_ID&api_key=token",
                transcoded = false,
                // A file has no transcode to end.
                playSessionId = null,
                defaultAudioStreamIndex = 1,
                defaultSubtitleStreamIndex = 3,
                streams = audio + subtitles,
            ),
            sources.single().seen(),
        )
    }

    @Test
    fun `an HEVC receiver with a cap and another audio track`() = runBlocking {
        answer = "playback/cast-transcode.json"

        val sources =
            playback.sources(
                PlaybackRequest(
                    itemId = itemId,
                    target = PlaybackTarget.CastReceiver(supportsHevc = true),
                    mediaSourceId = SOURCE_ID.toMediaSourceId(),
                    audioStreamIndex = 2,
                    maxBitrate = 8_000_000,
                )
            )

        assertEquals(
            StandInServer.resource("playback/cast-request-hevc-audio-capped.json"),
            sentBody(),
        )
        assertEquals(
            Seen(
                path =
                    "${server.baseUrl}/videos/${itemId.asString()}/master.m3u8?DeviceId=test" +
                        "&MediaSourceId=$SOURCE_ID&VideoCodec=h264&AudioCodec=aac" +
                        "&AudioStreamIndex=2&VideoBitrate=7808000&AudioBitrate=192000" +
                        "&SegmentContainer=ts&PlaySessionId=51c3f794f3da47dcb92c5c42f43f8c88" +
                        "&ApiKey=token&TranscodeReasons=AudioCodecNotSupported",
                transcoded = true,
                playSessionId = "51c3f794f3da47dcb92c5c42f43f8c88",
                defaultAudioStreamIndex = 1,
                defaultSubtitleStreamIndex = 4,
                streams = audio + subtitles,
            ),
            sources.single().seen(),
        )
    }

    private companion object {
        const val SOURCE_ID = "8f0862286a92020d0cb735d734765afe"
    }
}
