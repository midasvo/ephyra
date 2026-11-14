package dev.jdtech.jellyfin.presentation.settings

import android.app.Activity
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.ce.presentation.settings.TvSettingsLayout
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceGroup
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSelect
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsAction
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsEvent
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsState
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import dev.jdtech.jellyfin.utils.restart
import timber.log.Timber

@Composable
fun SettingsSubScreen(
    indexes: List<Int> = emptyList(),
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

    LaunchedEffect(true) { viewModel.loadPreferences(indexes) }

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
            // (current indexes + subtitle_style) is needed for SettingsViewModel.loadPreferences.
            is SettingsEvent.NavigateToSubtitleStyle ->
                navigateToSubSettings(indexes + SettingsR.string.subtitle_style)
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

    SettingsSubScreenLayout(
        title = indexes.last(),
        state = state,
        onAction = { action ->
            when (action) {
                is SettingsAction.OnUpdate -> {
                    viewModel.onAction(action)
                    viewModel.loadPreferences(indexes)
                }
                else -> Unit
            }
        },
    )
}

@Composable
private fun SettingsSubScreenLayout(
    @StringRes title: Int,
    state: SettingsState,
    onAction: (SettingsAction) -> Unit,
) {
    // CE: one layout for every settings page, see TvSettingsLayout.
    TvSettingsLayout(
        title = stringResource(id = title),
        parentTitle = stringResource(id = SettingsR.string.title_settings),
        groups = state.preferenceGroups,
        onAction = onAction,
    )
}

@Preview(device = "id:tv_1080p")
@Composable
private fun SettingsSubScreenLayoutPreview() {
    FindroidTheme {
        SettingsSubScreenLayout(
            title = SettingsR.string.title_settings,
            state =
                SettingsState(
                    preferenceGroups =
                        listOf(
                            PreferenceGroup(
                                preferences =
                                    listOf(
                                        PreferenceSelect(
                                            nameStringResource =
                                                SettingsR.string.pref_player_mpv_hwdec,
                                            backendPreference = Preference("", ""),
                                            options = SettingsR.array.mpv_hwdec,
                                            optionValues = SettingsR.array.mpv_hwdec,
                                        )
                                    )
                            )
                        )
                ),
            onAction = {},
        )
    }
}
