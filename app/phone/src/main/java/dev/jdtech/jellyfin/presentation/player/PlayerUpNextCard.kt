package dev.jdtech.jellyfin.presentation.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.core.presentation.theme.EphyraColor
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel.UpNextState
import dev.jdtech.jellyfin.presentation.film.components.ArtworkImage
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID

/**
 * CE (#272): the "Up next" card, bottom right over the video at the end of an episode, whether or
 * not the controls show. [state] is the ViewModel's; null hides the card. The card keeps showing
 * the last state while it fades out.
 *
 * VISION: a `medium` (16 dp) surface, the thumbnail `small` (12 dp), pill buttons, Play on
 * `primary`. The seconds left sit on the thumbnail in white over the scrim, and the bar along its
 * bottom, which is alive, is `primary`; without autoplay there is no countdown and nothing is cyan
 * but Play. Enters with a fade and an 8 dp drift up, leaves with a fade.
 */
@Composable
fun PlayerUpNextCard(
    state: UpNextState?,
    visible: Boolean,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = remember { LastValue<UpNextState>() }
    state?.let { last.value = it }
    val drift = with(LocalDensity.current) { EphyraMotion.EnterDrift.roundToPx() }

    AnimatedVisibility(
        visible = visible && state != null,
        enter =
            fadeIn(tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingEnter)) +
                slideInVertically(
                    tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingEnter)
                ) {
                    drift
                },
        exit = fadeOut(tween(EphyraMotion.DURATION_SHORT, easing = EphyraMotion.EasingExit)),
        modifier =
            modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom)
            ),
    ) {
        last.value?.let { UpNextCardContent(it, onPlayNow = onPlayNow, onCancel = onCancel) }
    }
}

private class LastValue<T> {
    var value: T? = null
}

@Composable
private fun UpNextCardContent(state: UpNextState, onPlayNow: () -> Unit, onCancel: () -> Unit) {
    // Compact, so that on a phone in landscape it stays below the controls' centre row.
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier.size(width = 112.dp, height = 63.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                ArtworkImage(
                    sources = listOf(state.imageUri),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                )
                state.secondsLeft?.let { seconds ->
                    val description =
                        pluralStringResource(
                            PlayerR.plurals.player_up_next_starts_in,
                            seconds,
                            seconds,
                        )
                    // Text on artwork: white over the scrim (VISION §3.4).
                    Box(
                        modifier =
                            Modifier.matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            EphyraColor.scrimOverArtTop,
                                            EphyraColor.scrimOverArtBottom,
                                        )
                                    )
                                )
                    )
                    Text(
                        text = seconds.toString(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        modifier =
                            Modifier.align(Alignment.Center).semantics {
                                contentDescription = description
                            },
                    )
                }
                state.fractionLeft?.let { fraction ->
                    CountdownBar(fraction, Modifier.align(Alignment.BottomStart))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text =
                        listOfNotNull(
                                stringResource(PlayerR.string.player_up_next),
                                state.episodeLabel,
                            )
                            .joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Not cyan: Cancel is not alive (VISION §1).
                    TextButton(
                        onClick = onCancel,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                    ) {
                        Text(stringResource(CoreR.string.cancel))
                    }
                    Button(
                        onClick = onPlayNow,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_play),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(PlayerR.string.player_up_next_play_now))
                    }
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
            modifier.fillMaxWidth(shown).height(3.dp).background(MaterialTheme.colorScheme.primary)
    )
}

@Preview
@Composable
private fun PlayerUpNextCardPreview() {
    FindroidTheme(darkTheme = true) {
        UpNextCardContent(
            UpNextState(
                itemId = UUID.randomUUID(),
                title = "The Pilot of a show with a rather long episode title",
                episodeLabel = "S01E02",
                imageUri = null,
                secondsLeft = 12,
                fractionLeft = 0.8f,
            ),
            onPlayNow = {},
            onCancel = {},
        )
    }
}
