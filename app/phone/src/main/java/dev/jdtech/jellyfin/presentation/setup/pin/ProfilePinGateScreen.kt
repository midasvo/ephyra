package dev.jdtech.jellyfin.presentation.setup.pin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
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
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.models.ProfileLockType
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.presentation.components.UserAvatar
import dev.jdtech.jellyfin.presentation.setup.components.RootLayout
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.profilelock.ProfilePinHasher
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.pin.PinError
import dev.jdtech.jellyfin.setup.presentation.pin.PinPrompt
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinGateAction
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinGateState
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinGateViewModel

/**
 * CE (#299): shown instead of Home when the app starts in a profile with a PIN. Back and "Other
 * profile" leave the profile closed and open the user list. Since #330 a profile locked with a
 * pattern gets the pattern grid instead of the PIN field.
 */
@Composable
fun ProfilePinGateScreen(viewModel: ProfilePinGateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val baseUrl = JellyfinApi.getInstance(LocalContext.current).api.baseUrl

    BackHandler { viewModel.onAction(ProfilePinGateAction.OnCancel) }

    ProfilePinGateLayout(state = state, baseUrl = baseUrl, onAction = viewModel::onAction)
}

@Composable
private fun ProfilePinGateLayout(
    state: ProfilePinGateState,
    baseUrl: String?,
    onAction: (ProfilePinGateAction) -> Unit,
) {
    val user = state.user ?: return
    val prompt = state.prompt ?: return
    val pin = rememberPinState(prompt)

    RootLayout {
        // RootLayout keeps clear of the keyboard (safeDrawing includes the IME). Scrolls when the
        // keyboard leaves too little room (a phone in landscape), centred otherwise.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val minHeight = maxHeight
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement =
                    Arrangement.spacedBy(MaterialTheme.spacings.medium, Alignment.CenterVertically),
                modifier =
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = minHeight)
                        .padding(horizontal = 24.dp, vertical = MaterialTheme.spacings.medium)
                        .testTag("ce_pin_gate"),
            ) {
                PinGateContent(
                    user = user,
                    prompt = prompt,
                    pin = pin,
                    baseUrl = baseUrl,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun PinGateContent(
    user: User,
    prompt: PinPrompt,
    pin: MutableState<String>,
    baseUrl: String?,
    onAction: (ProfilePinGateAction) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
    ) {
        val pattern = prompt.lockType == ProfileLockType.PATTERN
        UserAvatar(user = user, baseUrl = baseUrl, size = 96.dp, showLock = false)
        Text(
            text =
                stringResource(
                    if (pattern) SetupR.string.ce_profile_pattern_enter
                    else SetupR.string.ce_profile_pin_enter,
                    user.name,
                ),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (pattern) {
            PatternGrid(
                prompt = prompt,
                onPattern = { onAction(ProfilePinGateAction.OnPatternSubmit(it)) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PinField(
                prompt = prompt,
                pin = pin,
                onSubmit = { onAction(ProfilePinGateAction.OnPinSubmit(it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onAction(ProfilePinGateAction.OnPinSubmit(pin.value)) },
                enabled = ProfilePinHasher.isValid(pin.value),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(SetupR.string.confirm))
            }
        }
        OutlinedButton(
            onClick = { onAction(ProfilePinGateAction.OnCancel) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(SetupR.string.ce_profile_pin_other_profile))
        }
        Text(
            text =
                stringResource(
                    if (pattern) SetupR.string.ce_profile_pattern_forgot
                    else SetupR.string.ce_profile_pin_forgot
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview
@Composable
private fun ProfilePatternGateLayoutPreview() {
    FindroidTheme {
        ProfilePinGateLayout(
            state =
                ProfilePinGateState(
                    user = dummyUser,
                    prompt =
                        PinPrompt(dummyUser.id, dummyUser.name, lockType = ProfileLockType.PATTERN),
                ),
            baseUrl = null,
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun ProfilePinGateLayoutPreview() {
    FindroidTheme {
        ProfilePinGateLayout(
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
