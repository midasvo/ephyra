package dev.jdtech.jellyfin.presentation.utils

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalLocalNetworkBlock
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalNetworkBlock
import dev.jdtech.jellyfin.settings.domain.models.PermissionDefinitions
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.domain.shouldOpenSettingsAfterRefusal
import dev.jdtech.jellyfin.setup.presentation.localnetwork.LocalNetworkBlockViewModel
import dev.jdtech.jellyfin.utils.openAppSettings
import dev.jdtech.jellyfin.utils.shouldShowPermissionRationale

/**
 * CE (#376): the request for local network access behind an "Allow access" button. The system shows
 * no dialog once the permission was denied twice; only then the button opens the system settings.
 * [onGranted] runs when the dialog was answered with yes.
 */
@Composable
fun rememberLocalNetworkRequest(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val permission = PermissionDefinitions.localNetwork
    val currentOnGranted by rememberUpdatedState(onGranted)
    var rationaleBefore by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val activity = context as? Activity
            if (granted) {
                currentOnGranted()
            } else if (
                activity != null &&
                    shouldOpenSettingsAfterRefusal(
                        rationaleBefore = rationaleBefore,
                        rationaleAfter = shouldShowPermissionRationale(activity, permission),
                    )
            ) {
                openAppSettings(context)
            }
        }
    return remember(context, launcher) {
        {
            rationaleBefore =
                (context as? Activity)?.let { shouldShowPermissionRationale(it, permission) }
                    ?: false
            launcher.launch(permission.id)
        }
    }
}

/**
 * CE (#571): gives the error components the state of local network access for the server in use,
 * and the request behind their "Allow access" button. Access may change in the dialog or in the
 * system settings, so it is read again on every resume.
 */
@Composable
fun ProvideLocalNetworkBlock(content: @Composable () -> Unit) {
    val viewModel: LocalNetworkBlockViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    val request = rememberLocalNetworkRequest(onGranted = viewModel::refresh)
    val block =
        remember(state, request) {
            LocalNetworkBlock(
                blocked = state.blocked,
                settingName = state.settingName,
                refresh = viewModel::refresh,
                request = request,
            )
        }
    CompositionLocalProvider(LocalLocalNetworkBlock provides block, content = content)
}

/**
 * What a screen says while the block explains its error: that access is off, and where the system
 * settings keep it when the system tells.
 */
@Composable
fun localNetworkDeniedText(block: LocalNetworkBlock): String =
    stringResource(SetupR.string.ce_local_network_denied) +
        (block.settingName?.let {
            " " + stringResource(SetupR.string.ce_local_network_denied_where, it)
        } ?: "")
