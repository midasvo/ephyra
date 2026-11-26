package dev.jdtech.jellyfin.film.presentation.season

import dev.jdtech.jellyfin.models.EpisodeField
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression test for the missing season-level download button.
 *
 * `SeasonScreen` gates the header download button on `episodes.any { it.canDownload }`, but the
 * Jellyfin server only populates `BaseItemDto.CanDownload` on `GET /Shows/{id}/Episodes` when
 * `CanDownload` is explicitly requested in `fields` (see `DtoService` server-side). If
 * [seasonEpisodeFields] drops [EpisodeField.CAN_DOWNLOAD], every episode reports `canDownload =
 * false` and the button never appears.
 */
class SeasonEpisodeFieldsTest {

    @Test
    fun `season episode request includes Overview`() {
        assertTrue(seasonEpisodeFields.contains(EpisodeField.OVERVIEW))
    }

    @Test
    fun `season episode request includes CanDownload`() {
        assertTrue(
            seasonEpisodeFields.contains(EpisodeField.CAN_DOWNLOAD),
            message =
                "loadSeason must request EpisodeField.CAN_DOWNLOAD, otherwise SeasonScreen can never show the season download button",
        )
    }

    @Test
    fun `season episode request includes Trickplay`() {
        assertTrue(
            seasonEpisodeFields.contains(EpisodeField.TRICKPLAY),
            message =
                "loadSeason must request EpisodeField.TRICKPLAY so downloaded episodes carry their trickplay layout",
        )
    }
}
