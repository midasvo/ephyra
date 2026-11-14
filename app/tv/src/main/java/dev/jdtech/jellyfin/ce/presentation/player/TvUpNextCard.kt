package dev.jdtech.jellyfin.ce.presentation.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.ce.ui.focus.ephyraButtonBorder
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.UpNextState
import dev.jdtech.jellyfin.presentation.theme.spacings

/**
 * CE (#272): the "Up next" card on TV. With the overlay hidden it sits bottom right and "Play now"
 * has focus ([playNowFocus]), so OK plays the next episode and Back (handled by the screen's key
 * handler) cancels; with the overlay shown it sits above it, reachable with Up.
 *
 * VISION: a 16 dp surface, a 12 dp thumbnail, pill buttons with the Ephyra focus ring. The
 * countdown is alive, so its text and bar are `primary`.
 */
@Composable
internal fun TvUpNextCard(
    state: UpNextState,
    playNowFocus: FocusRequester,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(560.dp),
        shape = RoundedCornerShape(16.dp),
        colors =
            SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            ),
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacings.medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier.size(width = 192.dp, height = 108.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AsyncImage(
                        model = state.imageUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    state.fractionLeft?.let { fraction ->
                        CountdownBar(fraction, Modifier.align(Alignment.BottomStart))
                    }
                }
                Spacer(Modifier.width(MaterialTheme.spacings.medium))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text =
                            listOfNotNull(
                                    stringResource(PlayerR.string.player_up_next),
                                    state.episodeLabel,
                                )
                                .joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.secondsLeft?.let { seconds ->
                        Text(
                            text =
                                pluralStringResource(
                                    PlayerR.plurals.player_up_next_starts_in,
                                    seconds,
                                    seconds,
                                ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(MaterialTheme.spacings.medium))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(MaterialTheme.spacings.medium, Alignment.End),
            ) {
                Button(onClick = onCancel, border = ephyraButtonBorder()) {
                    Text(text = stringResource(CoreR.string.cancel))
                }
                Button(
                    onClick = onPlayNow,
                    border = ephyraButtonBorder(),
                    modifier = Modifier.focusRequester(playNowFocus),
                ) {
                    Icon(
                        painter = painterResource(id = CoreR.drawable.ic_play),
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(text = stringResource(PlayerR.string.player_up_next_play_now))
                }
            }
        }
    }
}

/** The countdown's share left, along the bottom of the thumbnail. */
@Composable
private fun CountdownBar(fraction: Float, modifier: Modifier = Modifier) {
    val shown by
        animateFloatAsState(
            targetValue = fraction,
            animationSpec =
                tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingStandard),
            label = "upNextCountdown",
        )
    Box(
        modifier =
            modifier.fillMaxWidth(shown).height(4.dp).background(MaterialTheme.colorScheme.primary)
    )
}
