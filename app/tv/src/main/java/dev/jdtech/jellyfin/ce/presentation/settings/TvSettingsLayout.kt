package dev.jdtech.jellyfin.ce.presentation.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme as MaterialThemePhone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.ce.ui.TvSafeArea
import dev.jdtech.jellyfin.ce.ui.focus.RestoreFocus
import dev.jdtech.jellyfin.ce.ui.focus.ephyraFocusedBorder
import dev.jdtech.jellyfin.ce.ui.focus.focusPosition
import dev.jdtech.jellyfin.ce.ui.focus.rememberFocusPosition
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.settings.components.SettingsMultiSelectDetailsCard
import dev.jdtech.jellyfin.presentation.settings.components.SettingsPermissionCard
import dev.jdtech.jellyfin.presentation.settings.components.SettingsSelectDetailsCard
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.presentation.models.Preference
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceAction
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceCategory
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceGroup
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceIntInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceLongInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceMultiSelect
import dev.jdtech.jellyfin.settings.presentation.models.PreferencePermission
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSelect
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSwitch
import dev.jdtech.jellyfin.settings.presentation.models.numberInputText
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsAction
import kotlin.math.abs

/**
 * The one layout of every TV settings page, the first as much as a category's: a list of the
 * settings on the left and, on the right, what the focused one needs room for. That is the options
 * of a select, or a description too long for its row.
 *
 * Upstream's first page was a grid of three columns with a group in each cell, so a group of three
 * rows stood next to groups of one and a switch was squeezed into a third of the screen. Its other
 * pages had a row per setting, each with its own indent.
 *
 * Focus comes back to the row it left from, after a visit to a category or to another screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvSettingsLayout(
    title: String,
    groups: List<PreferenceGroup>,
    onAction: (SettingsAction) -> Unit,
    parentTitle: String? = null,
) {
    val focusPosition = rememberFocusPosition()
    var focused by remember { mutableStateOf<Preference?>(null) }
    var paneFocused by remember { mutableStateOf(false) }
    // A reload hands out new copies of the preferences: show the current one, by name.
    val shown =
        focused?.let { old ->
            groups.firstNotNullOfOrNull { group ->
                group.preferences.firstOrNull { it.nameStringResource == old.nameStringResource }
            }
        }
    // The rows of all groups count on as one column, so each has a place of its own to return to.
    val firstRow = remember(groups) { groups.runningFold(0) { sum, g -> sum + g.preferences.size } }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    start = TvSafeArea.horizontal,
                    top = TvSafeArea.vertical,
                    end = TvSafeArea.horizontal,
                )
    ) {
        if (parentTitle != null) {
            Text(
                text = parentTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = title, style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large)) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                contentPadding = PaddingValues(bottom = TvSafeArea.vertical),
                // Left from the details pane goes back to the row it belongs to, not to the row
                // that happens to be level with the focused option (#419).
                modifier =
                    Modifier.weight(LIST_WEIGHT)
                        .focusProperties {
                            enter = { focusPosition.currentRequester() ?: FocusRequester.Default }
                        }
                        .focusGroup(),
            ) {
                itemsIndexed(groups) { index, group ->
                    TvSettingsGroup(
                        group = group,
                        onAction = onAction,
                        onFocused = { focused = it },
                        rowModifier = { row ->
                            Modifier.focusPosition(
                                focusPosition,
                                row = firstRow[index] + row,
                                column = 0,
                            )
                        },
                    )
                }
            }
            Box(
                modifier =
                    Modifier.weight(1f - LIST_WEIGHT).onFocusChanged {
                        paneFocused = it.hasFocus
                    }
            ) {
                TvSettingsDetails(preference = shown, onAction = onAction)
            }
        }
    }
    RestoreFocus(position = focusPosition, ready = groups.isNotEmpty())
    // Back in the details pane returns to the row that opened it, like a dialog (ADR 0003). From a
    // row, Back leaves the page.
    BackHandler(enabled = paneFocused) { focusPosition.requestFocus() }
}

/** A group: its name, then its rows on one panel, so it reads as one thing. */
@Composable
private fun TvSettingsGroup(
    group: PreferenceGroup,
    onAction: (SettingsAction) -> Unit,
    onFocused: (Preference) -> Unit,
    rowModifier: @Composable (row: Int) -> Modifier,
) {
    // One row with an icon gives every row of the group the slot, so their titles line up.
    val iconSlot = group.preferences.any { it.iconDrawableId != null }
    Column {
        group.nameStringResource?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        start = ROW_PADDING_HORIZONTAL,
                        bottom = MaterialTheme.spacings.small,
                    ),
            )
        }
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(GroupShape)
                    .background(MaterialThemePhone.colorScheme.surfaceContainerLow)
        ) {
            group.preferences.forEachIndexed { row, preference ->
                val modifier =
                    rowModifier(row).onFocusChanged { if (it.isFocused) onFocused(preference) }
                TvSettingsPreference(
                    preference = preference,
                    iconSlot = iconSlot,
                    onAction = onAction,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun TvSettingsPreference(
    preference: Preference,
    iconSlot: Boolean,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier,
) {
    val focusManager = LocalFocusManager.current
    // The options of a select are in the details pane: OK on its row goes there.
    val toDetails = { focusManager.moveFocus(FocusDirection.Right) }
    val description = preference.descriptionStringRes?.let { stringResource(it) }
    when (preference) {
        is PreferenceCategory ->
            TvSettingsRow(preference, description, iconSlot, modifier) {
                preference.onClick(preference)
            }
        is PreferenceAction ->
            TvSettingsRow(preference, description, iconSlot, modifier) {
                preference.onClick(preference)
            }
        is PreferenceSwitch ->
            TvSettingsRow(
                preference = preference,
                summary = description,
                iconSlot = iconSlot,
                modifier = modifier,
                trailing = { Switch(checked = preference.value, onCheckedChange = null) },
            ) {
                onAction(SettingsAction.OnUpdate(preference.copy(value = !preference.value)))
            }
        is PreferenceSelect ->
            TvSettingsRow(preference, selectedOption(preference), iconSlot, modifier) {
                toDetails()
            }
        is PreferenceMultiSelect ->
            TvSettingsRow(preference, selectedOptions(preference), iconSlot, modifier) {
                toDetails()
            }
        // A number is changed with the steps in the details pane, like the options of a select.
        is PreferenceIntInput ->
            TvSettingsRow(
                preference = preference,
                summary = inputValue(preference),
                iconSlot = iconSlot,
                modifier = modifier,
            ) {
                toDetails()
            }
        is PreferenceLongInput ->
            TvSettingsRow(
                preference = preference,
                summary = inputValue(preference.value, preference.prefixRes, preference.suffixRes),
                iconSlot = iconSlot,
                modifier = modifier,
            ) {
                toDetails()
            }
        is PreferencePermission ->
            SettingsPermissionCard(preference = preference, modifier = modifier)
    }
}

/**
 * A setting: icon, name, and under it its value or what it does, in a quieter tone. Two lines at
 * most; the details pane has the whole description.
 */
@Composable
private fun TvSettingsRow(
    preference: Preference,
    summary: String?,
    iconSlot: Boolean,
    modifier: Modifier,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = preference.enabled,
        shape = ClickableSurfaceDefaults.shape(shape = GroupShape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = MaterialThemePhone.colorScheme.surfaceContainerHigh,
                disabledContainerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder = ephyraFocusedBorder(GroupShape, inside = true)
            ),
        scale = ClickableSurfaceScale.None,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.heightIn(min = ROW_MIN_HEIGHT)
                    .padding(horizontal = ROW_PADDING_HORIZONTAL, vertical = ROW_PADDING_VERTICAL),
        ) {
            if (iconSlot) {
                Box(modifier = Modifier.size(ICON_SIZE)) {
                    preference.iconDrawableId?.let {
                        Icon(painter = painterResource(it), contentDescription = null)
                    }
                }
                Spacer(modifier = Modifier.width(ROW_PADDING_HORIZONTAL))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(preference.nameStringResource),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!summary.isNullOrBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
            trailing()
        }
    }
}

/** The options of a select, or the whole description of anything else that has one. */
@Composable
private fun TvSettingsDetails(preference: Preference?, onAction: (SettingsAction) -> Unit) {
    val bottom = Modifier.padding(bottom = TvSafeArea.vertical)
    when (preference) {
        null -> Unit
        is PreferenceSelect ->
            SettingsSelectDetailsCard(
                preference = preference,
                modifier = bottom.fillMaxSize(),
                onUpdate = { onAction(SettingsAction.OnUpdate(preference.copy(value = it))) },
            )
        is PreferenceMultiSelect ->
            SettingsMultiSelectDetailsCard(
                preference = preference,
                modifier = bottom.fillMaxSize(),
                onUpdate = { onAction(SettingsAction.OnUpdate(preference.copy(value = it))) },
            )
        is PreferenceIntInput ->
            TvSettingsNumber(
                preference = preference,
                value = preference.value.toLong(),
                text = inputValue(preference),
                inMilliseconds = preference.suffixRes == SettingsR.string.ms,
                onChange = {
                    onAction(SettingsAction.OnUpdate(preference.copy(value = it.toInt())))
                },
            )
        is PreferenceLongInput ->
            TvSettingsNumber(
                preference = preference,
                value = preference.value,
                text = inputValue(preference.value, preference.prefixRes, preference.suffixRes),
                inMilliseconds = preference.suffixRes == SettingsR.string.ms,
                onChange = { onAction(SettingsAction.OnUpdate(preference.copy(value = it))) },
            )
        else -> {
            val description = preference.descriptionStringRes ?: return
            Column(modifier = Modifier.padding(top = ROW_PADDING_VERTICAL)) {
                Text(
                    text = stringResource(preference.nameStringResource),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
                Text(
                    text = stringResource(description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A number setting in the details pane: its value between steps down and up. There is no keyboard
 * on a remote worth typing a number with. Most of these settings are small counts of episodes,
 * minutes or seconds; a value [inMilliseconds] (the timeouts, the next episode threshold) steps by
 * whole seconds instead (#420), and is still shown in milliseconds.
 */
@Composable
private fun TvSettingsNumber(
    preference: Preference,
    value: Long,
    text: String,
    inMilliseconds: Boolean,
    onChange: (Long) -> Unit,
) {
    val current = value.coerceAtLeast(0)
    val seconds = stringResource(SettingsR.string.seconds)
    Column(modifier = Modifier.padding(top = ROW_PADDING_VERTICAL)) {
        Text(
            text = stringResource(preference.nameStringResource),
            style = MaterialTheme.typography.headlineSmall,
        )
        preference.descriptionStringRes?.let {
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
        Text(text = text, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
            numberSteps(inMilliseconds).forEach { step ->
                val next = stepValue(current, step)
                Button(
                    onClick = { next?.let(onChange) },
                    enabled = preference.enabled && next != null,
                ) {
                    val size =
                        if (inMilliseconds) "${abs(step) / MS_PER_SECOND} $seconds"
                        else "${abs(step)}"
                    Text(text = if (step > 0) "+$size" else "−$size")
                }
            }
        }
    }
}

@Composable
private fun selectedOption(preference: PreferenceSelect): String {
    val notSet = stringResource(CoreR.string.not_set)
    if (preference.options == 0 || preference.optionValues == 0) return preference.value ?: notSet
    val names = stringArrayResource(preference.options)
    val index = stringArrayResource(preference.optionValues).indexOf(preference.value)
    return names.getOrNull(index) ?: notSet
}

@Composable
private fun selectedOptions(preference: PreferenceMultiSelect): String {
    val names = stringArrayResource(preference.options)
    val values = stringArrayResource(preference.optionValues)
    return values
        .mapIndexedNotNull { index, value ->
            names.getOrNull(index).takeIf { value in preference.value }
        }
        .joinToString(", ")
        .ifEmpty { stringResource(CoreR.string.none) }
}

@Composable
private fun inputValue(preference: PreferenceIntInput): String =
    inputValue(
        value = preference.value.toLong(),
        prefixRes = preference.prefixRes,
        suffixRes = preference.suffixRes,
        suffixPluralsRes = preference.suffixPluralsRes,
        zeroLabelRes = preference.zeroLabelRes,
    )

@Composable
private fun inputValue(
    value: Long,
    prefixRes: Int?,
    suffixRes: Int?,
    suffixPluralsRes: Int? = null,
    zeroLabelRes: Int? = null,
): String {
    val resources = LocalResources.current
    return numberInputText(
            value = value,
            prefixRes = prefixRes,
            suffixRes = suffixRes,
            string = resources::getString,
            quantityString = { id, quantity ->
                resources.getQuantityString(id, quantity, quantity)
            },
            suffixPluralsRes = suffixPluralsRes,
            zeroLabelRes = zeroLabelRes,
        )
        .ifEmpty { stringResource(CoreR.string.not_set) }
}

/** The steps of a number setting: 1 and 10, or 1 and 10 seconds for a value in milliseconds. */
internal fun numberSteps(inMilliseconds: Boolean): List<Long> =
    if (inMilliseconds) NUMBER_STEPS.map { it * MS_PER_SECOND } else NUMBER_STEPS

/**
 * The value after [step], or null when the step would take it below zero, the lowest value the
 * phone's number dialog accepts too.
 */
internal fun stepValue(current: Long, step: Long): Long? = (current + step).takeIf { it >= 0 }

private const val LIST_WEIGHT = .5f
// +1 first: OK on the row lands there, and it is never disabled.
private val NUMBER_STEPS = listOf(1L, -1L, 10L, -10L)
private const val MS_PER_SECOND = 1000L
private val GroupShape = RoundedCornerShape(12.dp)
private val ROW_MIN_HEIGHT = 56.dp
private val ROW_PADDING_HORIZONTAL = 16.dp
private val ROW_PADDING_VERTICAL = 10.dp
private val ICON_SIZE = 24.dp
