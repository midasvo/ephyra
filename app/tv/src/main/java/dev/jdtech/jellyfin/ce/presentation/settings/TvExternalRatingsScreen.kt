package dev.jdtech.jellyfin.ce.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.ce.ui.TvSafeArea
import dev.jdtech.jellyfin.ce.ui.focus.ephyraButtonBorder
import dev.jdtech.jellyfin.ce.ui.focus.ephyraFocusedBorder
import dev.jdtech.jellyfin.ce.ui.focus.ephyraFocusedGlow
import dev.jdtech.jellyfin.ce.ui.rememberTvTextFieldEditing
import dev.jdtech.jellyfin.ce.ui.tvTextFieldEditing
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.ExternalRatingsKeyStatus
import dev.jdtech.jellyfin.settings.presentation.ratings.ExternalRatingsSettingsAction
import dev.jdtech.jellyfin.settings.presentation.ratings.ExternalRatingsSettingsState
import dev.jdtech.jellyfin.settings.presentation.ratings.ExternalRatingsSettingsViewModel
import dev.jdtech.jellyfin.settings.presentation.ratings.ExternalRatingsTest

/**
 * CE (#331): Settings → Interface → Detail pages → External ratings on TV: the switch, what it
 * sends, the key (masked; OK opens the keyboard) and the Test button. A TV has no browser, so where
 * to get a key is only said, not linked.
 */
@Composable
fun TvExternalRatingsScreen(viewModel: ExternalRatingsSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TvExternalRatingsScreenLayout(state = state, onAction = viewModel::onAction)
}

@Composable
private fun TvExternalRatingsScreenLayout(
    state: ExternalRatingsSettingsState,
    onAction: (ExternalRatingsSettingsAction) -> Unit,
) {
    val switchRequester = remember { FocusRequester() }
    val keyEditing = rememberTvTextFieldEditing()

    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    start = TvSafeArea.horizontal,
                    top = TvSafeArea.vertical,
                    end = TvSafeArea.horizontal,
                )
                .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(SettingsR.string.ce_pref_external_ratings),
            style = MaterialTheme.typography.displayMedium,
        )
        Spacer(Modifier.height(MaterialTheme.spacings.large))
        Column(modifier = Modifier.width(720.dp)) {
            Surface(
                onClick = { onAction(ExternalRatingsSettingsAction.SetEnabled(!state.enabled)) },
                shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small),
                colors =
                    ClickableSurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                border = ClickableSurfaceDefaults.border(focusedBorder = ephyraFocusedBorder()),
                glow = ClickableSurfaceDefaults.glow(focusedGlow = ephyraFocusedGlow()),
                scale = ClickableSurfaceScale.None,
                modifier = Modifier.fillMaxWidth().focusRequester(switchRequester),
            ) {
                Row(
                    modifier = Modifier.padding(MaterialTheme.spacings.default),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(SettingsR.string.ce_external_ratings_switch),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text =
                                stringResource(SettingsR.string.ce_pref_external_ratings_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.enabled, onCheckedChange = null)
                }
            }
            Spacer(Modifier.height(MaterialTheme.spacings.medium))
            Text(
                text = stringResource(SettingsR.string.ce_external_ratings_privacy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacings.medium))
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = { onAction(ExternalRatingsSettingsAction.SetApiKey(it)) },
                label = { Text(stringResource(SettingsR.string.ce_external_ratings_api_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                readOnly = !keyEditing.editing,
                modifier = Modifier.fillMaxWidth().tvTextFieldEditing(keyEditing),
            )
            Spacer(Modifier.height(MaterialTheme.spacings.small))
            Text(
                text = stringResource(SettingsR.string.ce_external_ratings_get_key),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacings.medium))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                Button(
                    onClick = { onAction(ExternalRatingsSettingsAction.Test) },
                    enabled = state.canTest,
                    border = ephyraButtonBorder(),
                ) {
                    Text(stringResource(SettingsR.string.ce_external_ratings_test))
                }
                state.test?.let { test ->
                    val (text, isError) = test.tvMessage()
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(MaterialTheme.spacings.large))
        }
    }
    LaunchedEffect(Unit) { switchRequester.requestFocus() }
}

@Composable
private fun ExternalRatingsTest.tvMessage(): Pair<String, Boolean> =
    when (this) {
        ExternalRatingsTest.Running ->
            stringResource(SettingsR.string.ce_external_ratings_testing) to false
        is ExternalRatingsTest.Done ->
            when (status) {
                ExternalRatingsKeyStatus.VALID ->
                    stringResource(SettingsR.string.ce_external_ratings_test_valid) to false
                ExternalRatingsKeyStatus.INVALID ->
                    stringResource(SettingsR.string.ce_external_ratings_test_invalid) to true
                ExternalRatingsKeyStatus.RATE_LIMITED ->
                    stringResource(SettingsR.string.ce_external_ratings_test_rate_limited) to true
                ExternalRatingsKeyStatus.UNREACHABLE ->
                    stringResource(SettingsR.string.ce_external_ratings_test_unreachable) to true
            }
    }

@Preview(device = "id:tv_1080p")
@Composable
private fun TvExternalRatingsScreenLayoutPreview() {
    FindroidTheme {
        TvExternalRatingsScreenLayout(
            state =
                ExternalRatingsSettingsState(
                    enabled = true,
                    apiKey = "abcdef",
                    test = ExternalRatingsTest.Done(ExternalRatingsKeyStatus.VALID),
                ),
            onAction = {},
        )
    }
}
