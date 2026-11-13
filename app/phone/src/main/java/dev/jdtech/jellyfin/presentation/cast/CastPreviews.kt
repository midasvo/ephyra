package dev.jdtech.jellyfin.presentation.cast

import dev.jdtech.jellyfin.player.cast.models.CastAvailability
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.models.CastPlayerState
import dev.jdtech.jellyfin.player.cast.models.Device
import dev.jdtech.jellyfin.player.cast.presentation.CastDiscovery
import dev.jdtech.jellyfin.player.cast.presentation.CastNowPlaying
import dev.jdtech.jellyfin.player.cast.presentation.CastUiState
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.Track
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

/** States for the Cast surfaces' previews: one per thing a surface can say. */
internal object CastPreviews {
    private val tv = Device(id = "tv", name = "Living room TV")
    private val bedroom = Device(id = "bedroom", name = "Bedroom")

    private val episode =
        CastNowPlaying(
            itemId = UUID(0, 1),
            kind = BaseItemKind.EPISODE,
            title = "The Man From Mars",
            seriesName = "Pioneer One",
            episodeCode = "S01E02",
        )

    private val available = CastUiState(availability = CastAvailability.AVAILABLE)
    private val connected =
        available.copy(connectionState = CastConnectionState.CONNECTED, device = tv)

    val playing =
        connected.copy(
            nowPlaying = episode,
            player =
                CastPlayerState(
                    status = CastPlaybackStatus.PLAYING,
                    currentPosition = 754_000L,
                    duration = 2_280_000L,
                    volume = 0.6f,
                    hasNextItem = true,
                ),
            chapters =
                listOf(
                    PlayerChapter(startPosition = 0L, name = "Cold open"),
                    PlayerChapter(startPosition = 420_000L, name = "Arrival"),
                    PlayerChapter(startPosition = 1_500_000L),
                ),
            audioTracks =
                listOf(
                    Track(1, "English", "eng", "aac", selected = true, supported = true),
                    Track(2, "Nederlands", "nld", "eac3", selected = false, supported = true),
                ),
            subtitleTracks =
                listOf(
                    Track(102, "English", "eng", "subrip", selected = true, supported = true),
                    Track(103, "Nederlands", "nld", "pgssub", selected = false, supported = false),
                ),
        )

    val failed = connected.copy(nowPlaying = episode, playbackFailed = true, canRetry = true)

    val sheetStates =
        listOf(
            available.copy(discovery = CastDiscovery.SEARCHING),
            available.copy(discovery = CastDiscovery.SEARCHING, devices = listOf(tv, bedroom)),
            available.copy(
                discovery = CastDiscovery.DONE,
                devices = listOf(tv, bedroom),
                connectionState = CastConnectionState.CONNECTING,
                device = tv,
            ),
            available.copy(
                discovery = CastDiscovery.DONE,
                devices = listOf(tv, bedroom),
                connectFailedDevice = tv.name,
            ),
            available.copy(discovery = CastDiscovery.DONE),
            connected,
            playing,
            CastUiState(availability = CastAvailability.INSECURE_SERVER),
        )

    val miniStates =
        listOf(
            playing,
            playing.copy(player = playing.player.copy(status = CastPlaybackStatus.BUFFERING)),
            playing.copy(connectionState = CastConnectionState.CONNECTING),
            failed,
            connected.copy(finished = episode),
            connected,
        )
}
