package dev.jdtech.jellyfin.presentation.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceIntSelect
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSelect

/** CE (#837): an [Int] setting picked from a list, such as a streaming quality. */
@Composable
fun SettingsIntSelectCard(
    preference: PreferenceIntSelect,
    onUpdate: (value: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The dialog lists text values; the stored Int is written as its text and read back.
    val options = preference.options.map { (value, label) -> value.toString() to label.asString() }
    val selected = options.firstOrNull { it.first == preference.value.toString() }?.second
    var showDialog by remember { mutableStateOf(false) }

    SettingsBaseCard(
        preference = preference,
        onClick = { showDialog = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacings.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (preference.iconDrawableId != null) {
                Icon(
                    painter = painterResource(preference.iconDrawableId!!),
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.default))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(preference.nameStringResource),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
                Text(
                    text = selected ?: preference.value.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                preference.descriptionStringRes?.let {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showDialog) {
        SettingsSelectDialog(
            // The dialog takes a PreferenceSelect for its title and the selected row only.
            preference =
                PreferenceSelect(
                    nameStringResource = preference.nameStringResource,
                    backendPreference = Preference(preference.backendPreference.backendName, null),
                    options = 0,
                    optionValues = 0,
                    value = preference.value.toString(),
                ),
            options = options,
            onUpdate = { value ->
                showDialog = false
                value?.toIntOrNull()?.let(onUpdate)
            },
            onDismissRequest = { showDialog = false },
        )
    }
}

@Preview
@Composable
private fun SettingsIntSelectCardPreview() {
    FindroidTheme {
        SettingsIntSelectCard(
            preference =
                PreferenceIntSelect(
                    nameStringResource = SettingsR.string.ce_pref_player_quality_mobile,
                    descriptionStringRes = SettingsR.string.ce_pref_player_quality_mobile_summary,
                    backendPreference = Preference("", 0),
                    options =
                        listOf(
                            0 to
                                UiText.StringResource(
                                    SettingsR.string.ce_pref_player_quality_original
                                ),
                            8_000_000 to UiText.DynamicString("8 Mbps"),
                        ),
                    value = 8_000_000,
                ),
            onUpdate = {},
        )
    }
}
