package dev.jdtech.jellyfin.ce.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.ce.ui.focus.ephyraButtonBorder
import dev.jdtech.jellyfin.ce.ui.focus.ephyraFocusedBorder
import dev.jdtech.jellyfin.ce.ui.focus.ephyraFocusedGlow
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings

/**
 * CE (#418): the library's sort dialog with the TV focus ring, in place of upstream's
 * `SortByDialog`, whose rows only showed a faint grey band. The order is two pill buttons with the
 * ring and a check on the selected one; the options are rows like the settings lists (ring and
 * halo, the radio button marks the selected one). Each option is one focus stop.
 */
@Composable
fun TvSortByDialog(
    currentSortBy: SortBy,
    currentSortOrder: SortOrder,
    onUpdate: (sortBy: SortBy, sortOrder: SortOrder) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val options = SortBy.entries.zip(stringArrayResource(CoreR.array.sort_by_options))
    val orders = SortOrder.entries.zip(stringArrayResource(CoreR.array.sort_order_options))

    var selectedOption by remember { mutableStateOf(currentSortBy) }
    var selectedOrder by remember { mutableStateOf(currentSortOrder) }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(vertical = MaterialTheme.spacings.default)) {
                Text(
                    text = stringResource(CoreR.string.sort_by),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacings.default),
                )
                Spacer(modifier = Modifier.size(MaterialTheme.spacings.medium))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacings.default),
                ) {
                    orders.forEach { (order, name) ->
                        SortOrderButton(
                            name = name,
                            isSelected = order == selectedOrder,
                            onClick = {
                                selectedOrder = order
                                onUpdate(selectedOption, selectedOrder)
                            },
                            modifier = Modifier.testTag(sortOrderTag(order)),
                        )
                    }
                }
                Spacer(modifier = Modifier.size(MaterialTheme.spacings.small))
                // Room around the rows, so the list doesn't clip the ring and halo.
                LazyColumn(
                    contentPadding =
                        PaddingValues(
                            horizontal = MaterialTheme.spacings.default,
                            vertical = MaterialTheme.spacings.small,
                        ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
                ) {
                    items(options, key = { it.first }) { (option, name) ->
                        SortOptionItem(
                            name = name,
                            isSelected = option == selectedOption,
                            onClick = {
                                selectedOption = option
                                onUpdate(selectedOption, selectedOrder)
                            },
                            modifier = Modifier.testTag(sortOptionTag(option)),
                        )
                    }
                }
            }
        }
    }
}

/** A pill button: the focus ring when focused, the check and the filled container when selected. */
@Composable
private fun SortOrderButton(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        border = ephyraButtonBorder(),
        colors =
            if (isSelected) {
                ButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                ButtonDefaults.colors()
            },
        modifier = modifier,
    ) {
        if (isSelected) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
        }
        Text(text = name)
    }
}

/** One sort option, built like the settings list items: ring and halo on focus. */
@Composable
private fun SortOptionItem(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(4.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
            ),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder = ephyraFocusedBorder(RoundedCornerShape(10.dp))
            ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = ephyraFocusedGlow()),
        scale = ClickableSurfaceScale.None,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(MaterialTheme.spacings.small),
        ) {
            // Not clickable itself: the row is the one focus stop.
            RadioButton(selected = isSelected, onClick = null)
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

internal fun sortOptionTag(sortBy: SortBy) = "ce_sort_option_${sortBy.name}"

internal fun sortOrderTag(sortOrder: SortOrder) = "ce_sort_order_${sortOrder.name}"

@Preview(device = "id:tv_1080p")
@Composable
private fun TvSortByDialogPreview() {
    FindroidTheme {
        TvSortByDialog(
            currentSortBy = SortBy.NAME,
            currentSortOrder = SortOrder.ASCENDING,
            onUpdate = { _, _ -> },
            onDismissRequest = {},
        )
    }
}
