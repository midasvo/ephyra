package dev.jdtech.jellyfin.presentation.film.components

import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.domain.MediaInfo
import dev.jdtech.jellyfin.film.domain.MediaInfoNumbers
import dev.jdtech.jellyfin.film.domain.MediaInfoTrack
import dev.jdtech.jellyfin.film.domain.toMediaInfo
import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import java.util.Locale
import org.jellyfin.sdk.model.api.MediaStreamType

const val MEDIA_INFO_SHEET_TAG = "media_info_sheet"
const val MEDIA_INFO_FILE_TAG = "media_info_file"

/** A version's name is a file name on many servers; a pill shows this much of it. */
private val VersionPillMaxWidth = 240.dp

/**
 * CE (#510): the file behind a movie or episode, opened from the info chip at the end of the badge
 * row. It replaces the "Display Extra Info" setting, which put the same facts on every page as
 * four lines of text.
 *
 * Everything comes from [sources], which the page already has; nothing is worked out until the
 * sheet opens. An item with several versions gets a pill per version, and the sheet opens on the
 * first, the one the page's badges describe. Pass the sources that can be described
 * ([dev.jdtech.jellyfin.film.domain.withMediaInfo]); with none of them the page shows no chip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInfoSheet(sources: List<FindroidSource>, onDismissRequest: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        // Opens at its full height: half a landscape window shows the title and little else.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        MediaInfoContent(sources = sources, modifier = Modifier.testTag(MEDIA_INFO_SHEET_TAG))
    }
}

@Composable
internal fun MediaInfoContent(sources: List<FindroidSource>, modifier: Modifier = Modifier) {
    if (sources.isEmpty()) return
    var picked by rememberSaveable { mutableIntStateOf(0) }
    // The list can be another one after a reload: never point past its end.
    val selected = picked.coerceIn(sources.indices)
    val source = sources[selected]
    val locale =
        ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val info = remember(source, locale) { source.toMediaInfo(locale) }
    val numbers = remember(locale) { MediaInfoNumbers(locale) }
    val file = fileLine(info)

    // The facts can be selected, to copy a line into a bug report or a search. The title and the
    // version pills are controls, not facts.
    SelectionContainer(modifier = modifier) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = MaterialTheme.spacings.medium,
                        end = MaterialTheme.spacings.medium,
                        bottom = MaterialTheme.spacings.default,
                    ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        ) {
            // One block: the title, then what it is about. A pill is 32 dp high in a 48 dp touch
            // target, so the 8 dp above and below it are part of the spacing here.
            Column {
                DisableSelection {
                    Text(
                        text = stringResource(CoreR.string.ce_media_info),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                // With several versions the pills come first and the file line under them: it
                // describes the version that is picked.
                if (sources.size > 1) {
                    DisableSelection {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            sources.forEachIndexed { index, version ->
                                FilterChip(
                                    selected = index == selected,
                                    onClick = { picked = index },
                                    // The tick carries the choice, not the colour alone.
                                    leadingIcon =
                                        if (index == selected) {
                                            {
                                                Icon(
                                                    painter =
                                                        painterResource(CoreR.drawable.ic_check),
                                                    contentDescription = null,
                                                    modifier =
                                                        Modifier.size(FilterChipDefaults.IconSize),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    label = {
                                        Text(
                                            text = versionLabel(version, index, numbers),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = VersionPillMaxWidth),
                                        )
                                    },
                                    shape = CircleShape,
                                )
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
                }
                FileLine(file)
            }
            MediaInfoSection(stringResource(CoreR.string.video), info.video, numbers)
            MediaInfoSection(stringResource(CoreR.string.audio), info.audio, numbers)
            MediaInfoSection(stringResource(CoreR.string.subtitle), info.subtitles, numbers)
        }
    }
}

/** What a version's pill says: its name, or its number when the server gave it none. */
private fun versionLabel(version: FindroidSource, index: Int, numbers: MediaInfoNumbers): String =
    version.name.ifBlank { numbers.count(index + 1) }

/** "MKV · 54.01 GB · 62.1 Mbps", with whatever the server left out left out. */
@Composable
private fun fileLine(info: MediaInfo): String {
    val context = LocalContext.current
    val size = info.sizeBytes.takeIf { it > 0 }?.let { Formatter.formatFileSize(context, it) }
    return listOfNotNull(info.container, size, info.bitrate).joinToString(" · ")
}

@Composable
private fun FileLine(text: String) {
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(MEDIA_INFO_FILE_TAG),
    )
}

/** One kind of track: its name, how many there are when there are several, and a card of them. */
@Composable
private fun MediaInfoSection(
    title: String,
    tracks: List<MediaInfoTrack>,
    numbers: MediaInfoNumbers,
) {
    if (tracks.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.alignByBaseline().semantics { heading() },
            )
            if (tracks.size > 1) {
                Text(
                    text = numbers.count(tracks.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                tracks.forEachIndexed { index, track ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier =
                                Modifier.padding(horizontal = MaterialTheme.spacings.medium),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    MediaInfoTrackRow(track)
                }
            }
        }
    }
}

@Composable
private fun MediaInfoTrackRow(track: MediaInfoTrack) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacings.medium, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = track.title, style = MaterialTheme.typography.bodyMedium)
        if (track.details.isNotEmpty()) {
            Text(
                text = track.details.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MediaInfoContentPreview() {
    FindroidTheme { MediaInfoContent(sources = listOf(previewSource("2160p Remux"))) }
}

@Preview(showBackground = true)
@Composable
private fun MediaInfoContentVersionsPreview() {
    FindroidTheme {
        MediaInfoContent(sources = listOf(previewSource("2160p Remux"), previewSource("1080p")))
    }
}

private fun previewSource(name: String): FindroidSource {
    fun stream(type: MediaStreamType, title: String) =
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
            height = if (type == MediaStreamType.VIDEO) 2160 else null,
            width = if (type == MediaStreamType.VIDEO) 3840 else null,
            videoDoViTitle = null,
            bitRate = if (type == MediaStreamType.SUBTITLE) null else 4_210_000,
            frameRate = 23.976f,
            bitDepth = if (type == MediaStreamType.VIDEO) 10 else 24,
            sampleRate = 48_000,
        )
    return FindroidSource(
        id = name,
        name = name,
        type = FindroidSourceType.REMOTE,
        path = "",
        size = 58_000_000_000,
        mediaStreams =
            listOf(
                stream(MediaStreamType.VIDEO, "4K HEVC Dolby Vision"),
                stream(MediaStreamType.AUDIO, "English - TrueHD 7.1 - Default"),
                stream(MediaStreamType.AUDIO, "English - Dolby Digital 5.1"),
                stream(MediaStreamType.SUBTITLE, "English - PGSSUB"),
                stream(MediaStreamType.SUBTITLE, "Dutch - Forced - SUBRIP"),
            ),
        container = "mkv",
        bitrate = 62_100_000,
    )
}
