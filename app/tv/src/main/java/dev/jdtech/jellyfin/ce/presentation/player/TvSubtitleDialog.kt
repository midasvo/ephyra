package dev.jdtech.jellyfin.ce.presentation.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.presentation.theme.spacings
import java.util.Locale

/**
 * CE (#619): Subtitle track selection and delay adjustment dialog for the TV player.
 */
@Composable
fun TvSubtitleDialog(
    options: List<String>,
    selectedIndex: Int,
    subtitleDelayMs: Int,
    onSelectTrack: (index: Int) -> Unit,
    onDelayChanged: (delayMs: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(580.dp),
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.spacings.large)) {
                Text(
                    text = stringResource(PlayerR.string.select_subtitle_track),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))

                // Subtitle delay stepper row
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = MaterialTheme.spacings.medium),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = stringResource(PlayerR.string.ce_subtitle_delay),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val delaySec = subtitleDelayMs / 1000.0
                            val delayText =
                                if (subtitleDelayMs > 0) String.format(Locale.US, "+%.1fs", delaySec)
                                else String.format(Locale.US, "%.1fs", delaySec)
                            Text(
                                text = delayText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = { onDelayChanged((subtitleDelayMs - 100).coerceAtLeast(-180_000)) },
                                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Text("-0.1s")
                            }
                            Button(
                                onClick = { onDelayChanged((subtitleDelayMs + 100).coerceAtMost(180_000)) },
                                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Text("+0.1s")
                            }
                            if (subtitleDelayMs != 0) {
                                Button(
                                    onClick = { onDelayChanged(0) },
                                    colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                ) {
                                    Text(stringResource(PlayerR.string.ce_reset))
                                }
                            }
                        }
                    }
                }

                val initial = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial)
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { if (options.isNotEmpty()) focusRequester.requestFocus() }

                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
                    modifier = Modifier.heightIn(max = 380.dp),
                ) {
                    itemsIndexed(options) { index, option ->
                        ListItem(
                            selected = index == selectedIndex,
                            onClick = {
                                onSelectTrack(index)
                                onDismiss()
                            },
                            headlineContent = {
                                Text(text = option, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                RadioButton(selected = index == selectedIndex, onClick = null)
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                                    .then(
                                        if (index == initial) Modifier.focusRequester(focusRequester)
                                        else Modifier
                                    ),
                        )
                    }
                }
            }
        }
    }
}
