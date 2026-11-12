package dev.jdtech.jellyfin.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.presentation.cast.components.formatDuration

/**
 * Shared chapter item card with preview thumbnail, title, timestamp, and current playback selection
 * indicator.
 */
@Composable
fun ChapterCard(
    chapter: PlayerChapter,
    index: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val startTimeMs = chapter.startPosition
    val formattedTime = formatDuration(startTimeMs)

    val chapterTitle =
        chapter.name?.takeIf { it.isNotBlank() }
            ?: stringResource(id = PlayerR.string.chapter_number, index + 1)

    var isImageError by remember(chapter.imageUri) { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // The chapter that is playing is alive, so it gets the one cyan ring (VISION §1.2).
        border = if (isCurrent) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        onClick = onClick,
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val hasServerImage = !chapter.imageUri.isNullOrBlank() && !isImageError
            Box(
                modifier =
                    Modifier.height(42.dp)
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                if (hasServerImage) {
                    AsyncImage(
                        model = chapter.imageUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onError = { isImageError = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
