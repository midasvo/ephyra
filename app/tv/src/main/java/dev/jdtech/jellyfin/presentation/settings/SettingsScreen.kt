package dev.jdtech.jellyfin.presentation.settings

import android.app.Activity
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.ce.presentation.settings.TvSettingsLayout
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceCategory
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceGroup
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsAction
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsEvent
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsState
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import dev.jdtech.jellyfin.utils.restart
import timber.log.Timber

@Composable
fun SettingsScreen(
    navigateToSubSettings: (indexes: List<Int>) -> Unit,
    navigateToServers: () -> Unit,
    navigateToUsers: () -> Unit,
    navigateToHomeRows: () -> Unit,
    navigateToProfilePin: () -> Unit,
    navigateToAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadPreferences(emptyList()) }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is SettingsEvent.NavigateToSettings -> navigateToSubSettings(event.indexes)
            is SettingsEvent.NavigateToSettingsFileEdit -> {}
            is SettingsEvent.NavigateToUsers -> navigateToUsers()
            is SettingsEvent.NavigateToServers -> navigateToServers()
            is SettingsEvent.NavigateToAbout -> navigateToAbout()
            is SettingsEvent.NavigateToHomeRows -> navigateToHomeRows()
            is SettingsEvent.NavigateToProfilePin -> navigateToProfilePin()
            // TV has no custom subtitle-style screen; route through the standard sub-settings
            // list, which renders the subtitle_style category's nestedPreferenceGroups. The
            // subtitle_style category is nested under the player category, so the full path
            // is needed for SettingsViewModel.loadPreferences to walk down to it.
            is SettingsEvent.NavigateToSubtitleStyle ->
                navigateToSubSettings(
                    listOf(
                        SettingsR.string.settings_category_player,
                        SettingsR.string.subtitle_style,
                    )
                )
            is SettingsEvent.UpdateTheme -> Unit
            is SettingsEvent.CacheCleared ->
                Toast.makeText(context, SettingsR.string.settings_cache_cleared, Toast.LENGTH_SHORT)
                    .show()
            is SettingsEvent.LaunchIntent -> {
                try {
                    context.startActivity(event.intent)
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
            is SettingsEvent.RestartActivity -> {
                try {
                    (context as Activity).restart()
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        }
    }

    SettingsScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is SettingsAction.OnUpdate -> {
                    viewModel.onAction(action)
                    viewModel.loadPreferences(emptyList())
                }
                else -> Unit
            }
        },
    )
}

@Composable
private fun SettingsScreenLayout(state: SettingsState, onAction: (SettingsAction) -> Unit) {
    // CE: the first page uses the same list as every category, see TvSettingsLayout.
    TvSettingsLayout(
        title = stringResource(id = SettingsR.string.title_settings),
        groups = state.preferenceGroups,
        onAction = onAction,
    )
}

@Preview(device = "id:tv_1080p")
@Composable
private fun SettingsScreenLayoutPreview() {
    FindroidTheme {
        SettingsScreenLayout(
            state =
                SettingsState(
                    preferenceGroups =
                        listOf(
                            PreferenceGroup(
                                nameStringResource = null,
                                preferences =
                                    listOf(
                                        PreferenceCategory(
                                            nameStringResource =
                                                SettingsR.string.settings_category_language,
                                            iconDrawableId = SettingsR.drawable.ic_languages,
                                        )
                                    ),
                            ),
                            PreferenceGroup(
                                nameStringResource = null,
                                preferences =
                                    listOf(
                                        PreferenceCategory(
                                            nameStringResource =
                                                SettingsR.string.settings_category_appearance,
                                            iconDrawableId = SettingsR.drawable.ic_palette,
                                        )
                                    ),
                            ),
                        )
                ),
            onAction = {},
        )
    }
}
