package dev.jdtech.jellyfin.ce.presentation.pin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.ce.ui.TvSafeArea
import dev.jdtech.jellyfin.ce.ui.components.UserAvatar
import dev.jdtech.jellyfin.ce.ui.focus.ephyraButtonBorder
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.pin.PinError
import dev.jdtech.jellyfin.setup.presentation.pin.PinPrompt
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinGateAction
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinGateState
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinGateViewModel

/**
 * CE (#299): shown full screen instead of Home (and before a Watch Next program plays) when the app
 * starts in a profile with a PIN. Back and "Other profile" leave the profile closed and open the
 * user list.
 */
@Composable
fun TvProfilePinGateScreen(viewModel: ProfilePinGateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val baseUrl = JellyfinApi.getInstance(LocalContext.current).api.baseUrl

    BackHandler { viewModel.onAction(ProfilePinGateAction.OnCancel) }

    TvProfilePinGateLayout(state = state, baseUrl = baseUrl, onAction = viewModel::onAction)
}

@Composable
private fun TvProfilePinGateLayout(
    state: ProfilePinGateState,
    baseUrl: String?,
    onAction: (ProfilePinGateAction) -> Unit,
) {
    val user = state.user ?: return
    val prompt = state.prompt ?: return
    val pin = rememberTvPinState(prompt)

    Box(
        modifier = Modifier.fillMaxSize().padding(TvSafeArea.paddingValues).testTag("ce_pin_gate")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 420.dp),
            ) {
                UserAvatar(user = user, baseUrl = baseUrl, size = 128.dp, showLock = false)
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
                Text(
                    text = stringResource(SetupR.string.ce_profile_pin_enter, user.name),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))
                OutlinedButton(
                    onClick = { onAction(ProfilePinGateAction.OnCancel) },
                    border = ephyraButtonBorder(),
                ) {
                    Text(text = stringResource(SetupR.string.ce_profile_pin_other_profile))
                }
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
                Text(
                    text = stringResource(SetupR.string.ce_profile_pin_forgot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.width(96.dp))
            TvPinPad(
                prompt = prompt,
                pin = pin,
                onSubmit = { onAction(ProfilePinGateAction.OnPinSubmit(it)) },
            )
        }
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun TvProfilePinGateLayoutPreview() {
    FindroidTheme {
        TvProfilePinGateLayout(
            state =
                ProfilePinGateState(
                    user = dummyUser,
                    prompt = PinPrompt(dummyUser.id, dummyUser.name, PinError.WRONG, 1),
                ),
            baseUrl = null,
            onAction = {},
        )
    }
}
