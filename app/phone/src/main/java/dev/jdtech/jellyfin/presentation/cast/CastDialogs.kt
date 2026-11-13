package dev.jdtech.jellyfin.presentation.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.Track
import dev.jdtech.jellyfin.player.core.domain.models.formatStartTimestamp
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.utils.TrackFormatter

/**
 * CE (#626): the audio or subtitle tracks of what the receiver plays, as the single-choice dialog
 * the local player and the settings use. Subtitles start with "None"; a subtitle the receiver
 * cannot be given is listed but cannot be picked.
 */
@Composable
fun CastTrackDialog(
    title: String,
    tracks: List<Track>,
    isSubtitle: Boolean,
    onSelect: (Track?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val forced = stringResource(PlayerR.string.ce_track_forced)
    val listState = rememberLazyListState()
    BaseDialog(title = title, onDismiss = onDismiss) {
        if (listState.canScrollBackward) HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(),
            state = listState,
            contentPadding = PaddingValues(bottom = MaterialTheme.spacings.default),
        ) {
            if (isSubtitle) {
                item(key = "none") {
                    TrackRow(
                        name = stringResource(CoreR.string.none),
                        details = null,
                        selected = tracks.none { it.selected },
                        enabled = true,
                        onClick = { onSelect(null) },
                    )
                }
            }
            itemsIndexed(items = tracks, key = { _, track -> track.id }) { index, track ->
                val info =
                    remember(track) {
                        TrackFormatter.getTrackDisplayInfo(
                            track = track,
                            context = context,
                            forcedLabel = forced,
                            isSubtitle = isSubtitle,
                            fallbackIndex = index + 1,
                        )
                    }
                TrackRow(
                    name = info.name,
                    details = info.chips.joinToString(" · ").ifEmpty { null },
                    selected = track.selected,
                    enabled = track.supported,
                    onClick = { onSelect(track) },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    name: String,
    details: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .padding(horizontal = MaterialTheme.spacings.default)
                .alpha(if (enabled) 1f else 0.38f),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row is the control: the button only draws the state.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(vertical = MaterialTheme.spacings.small)) {
            Text(
                text = name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (details != null) {
                Text(
                    text = details,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * CE (#626): the chapters of what the receiver plays, each with the server's thumbnail where it
 * made one. The list opens on the chapter that is playing.
 */
@Composable
fun CastChaptersDialog(
    chapters: List<PlayerChapter>,
    positionMs: Long,
    onSelect: (PlayerChapter) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = chapters.indexOfLast { it.startPosition <= positionMs }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceAtLeast(0))
    BaseDialog(title = stringResource(PlayerR.string.select_chapter), onDismiss = onDismiss) {
        if (listState.canScrollBackward) HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            state = listState,
            contentPadding = PaddingValues(bottom = MaterialTheme.spacings.default),
        ) {
            itemsIndexed(items = chapters) { index, chapter ->
                ChapterRow(
                    chapter = chapter,
                    number = index + 1,
                    playing = index == current,
                    onClick = { onSelect(chapter) },
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: PlayerChapter, number: Int, playing: Boolean, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { selected = playing }
                .padding(
                    horizontal = MaterialTheme.spacings.default,
                    vertical = MaterialTheme.spacings.small,
                ),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chapter.imageUri != null) {
            AsyncImage(
                model = chapter.imageUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.width(96.dp)
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    chapter.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(PlayerR.string.chapter_number, number),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = chapter.formatStartTimestamp(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            )
        }
        // The chapter that plays now: the play mark in `primary` (VISION §7, a state that is
        // alive), so it is not told by colour alone.
        Box(modifier = Modifier.size(24.dp)) {
            if (playing) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_play),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Preview
@Composable
private fun CastTrackDialogPreview() {
    FindroidTheme {
        CastTrackDialog(
            title = "Select subtitle track",
            tracks = CastPreviews.playing.subtitleTracks,
            isSubtitle = true,
            onSelect = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun CastChaptersDialogPreview() {
    FindroidTheme {
        CastChaptersDialog(
            chapters = CastPreviews.playing.chapters,
            positionMs = CastPreviews.playing.player.currentPosition,
            onSelect = {},
            onDismiss = {},
        )
    }
}
