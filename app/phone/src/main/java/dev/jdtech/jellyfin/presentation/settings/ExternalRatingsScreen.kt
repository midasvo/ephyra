package dev.jdtech.jellyfin.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.Layout
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.ExternalRatingsKeyStatus
import dev.jdtech.jellyfin.settings.presentation.ratings.ExternalRatingsSettingsAction
import dev.jdtech.jellyfin.settings.presentation.ratings.ExternalRatingsSettingsState
import dev.jdtech.jellyfin.settings.presentation.ratings.ExternalRatingsSettingsViewModel
import dev.jdtech.jellyfin.settings.presentation.ratings.ExternalRatingsTest

/** Where an MDBList account hands out its API key. */
const val MDBLIST_KEY_URL = "https://mdblist.com/preferences/#api"

/**
 * CE (#331): Settings → Interface → Detail pages → External ratings. The switch, what it sends, the
 * user's key (masked) with a link to get one, and the Test button, the only place an error shows.
 */
@Composable
fun ExternalRatingsScreen(
    navigateBack: () -> Unit,
    viewModel: ExternalRatingsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExternalRatingsScreenLayout(
        state = state,
        onAction = viewModel::onAction,
        onBackClick = navigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExternalRatingsScreenLayout(
    state: ExternalRatingsSettingsState,
    onAction: (ExternalRatingsSettingsAction) -> Unit,
    onBackClick: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val uriHandler = LocalUriHandler.current

    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .recalculateWindowInsets()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(SettingsR.string.ce_pref_external_ratings)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()),
            // The settings cap (4-design.md §12), centred as on the Settings screen.
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = Layout.settingsMaxWidth).fillMaxWidth()) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(SettingsR.string.ce_external_ratings_switch))
                    },
                    supportingContent = {
                        Text(stringResource(SettingsR.string.ce_pref_external_ratings_summary))
                    },
                    trailingContent = {
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = {
                                onAction(ExternalRatingsSettingsAction.SetEnabled(it))
                            },
                        )
                    },
                )
                // In line with the switch's ListItem.
                val padding = MaterialTheme.spacings.medium
                Column(modifier = Modifier.padding(horizontal = padding)) {
                    Text(
                        text = stringResource(SettingsR.string.ce_external_ratings_privacy),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    OutlinedTextField(
                        value = state.apiKey,
                        onValueChange = { onAction(ExternalRatingsSettingsAction.SetApiKey(it)) },
                        label = {
                            Text(stringResource(SettingsR.string.ce_external_ratings_api_key))
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = {
                            // No browser to open it in: the text says where to go anyway.
                            runCatching { uriHandler.openUri(MDBLIST_KEY_URL) }
                        }
                    ) {
                        Text(stringResource(SettingsR.string.ce_external_ratings_get_key))
                    }
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    Button(
                        onClick = { onAction(ExternalRatingsSettingsAction.Test) },
                        enabled = state.canTest,
                    ) {
                        Text(stringResource(SettingsR.string.ce_external_ratings_test))
                    }
                    state.test?.let { test ->
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                        val (text, isError) = test.message()
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                if (isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(MaterialTheme.spacings.default))
                }
            }
        }
    }
}

/** The line under the Test button, and whether it is an error. */
@Composable
private fun ExternalRatingsTest.message(): Pair<String, Boolean> =
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

@Preview
@Composable
private fun ExternalRatingsScreenLayoutPreview() {
    FindroidTheme {
        ExternalRatingsScreenLayout(
            state =
                ExternalRatingsSettingsState(
                    enabled = true,
                    apiKey = "abcdef",
                    test = ExternalRatingsTest.Done(ExternalRatingsKeyStatus.INVALID),
                ),
            onAction = {},
            onBackClick = {},
        )
    }
}
