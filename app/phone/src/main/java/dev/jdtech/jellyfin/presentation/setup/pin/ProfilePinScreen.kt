package dev.jdtech.jellyfin.presentation.setup.pin

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.models.ProfileLockType
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.Layout
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.pin.PinStep
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinSettingsAction
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinSettingsEvent
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinSettingsState
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinSettingsViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents

/**
 * CE (#299): Settings → Profile PIN for the signed-in profile. Since #330 it is Profile lock on
 * phone, with the choice between a PIN and a pattern. TV keeps its own Profile PIN screen.
 */
@Composable
fun ProfilePinScreen(
    navigateBack: () -> Unit,
    viewModel: ProfilePinSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        val pattern = viewModel.state.value.changedLockType == ProfileLockType.PATTERN
        val message =
            when (event) {
                ProfilePinSettingsEvent.PinSet ->
                    if (pattern) SetupR.string.ce_profile_pattern_saved
                    else SetupR.string.ce_profile_pin_saved
                ProfilePinSettingsEvent.PinRemoved ->
                    if (pattern) SetupR.string.ce_profile_pattern_removed
                    else SetupR.string.ce_profile_pin_removed
            }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    ProfilePinLayout(
        state = state,
        onAction = { action ->
            if (action is ProfilePinSettingsAction.OnBackClick) navigateBack()
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilePinLayout(
    state: ProfilePinSettingsState,
    onAction: (ProfilePinSettingsAction) -> Unit,
) {
    val lockType = state.lockType
    Scaffold(
        modifier = Modifier.fillMaxSize().recalculateWindowInsets(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(SetupR.string.ce_profile_lock)) },
                navigationIcon = {
                    IconButton(onClick = { onAction(ProfilePinSettingsAction.OnBackClick) }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier.padding(innerPadding)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // The settings cap (4-design.md §12), centred as on the Settings screen.
                    .wrapContentWidth()
                    .widthIn(max = Layout.settingsMaxWidth)
                    .testTag("ce_profile_pin_settings")
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(
                            when (lockType) {
                                ProfileLockType.PIN -> SetupR.string.ce_profile_pin_status_on
                                ProfileLockType.PATTERN ->
                                    SetupR.string.ce_profile_lock_status_pattern
                                null -> SetupR.string.ce_profile_lock_status_off
                            }
                        )
                    )
                },
                supportingContent = { Text(stringResource(SetupR.string.ce_profile_lock_summary)) },
                overlineContent = state.user?.let { user -> { Text(user.name) } },
                leadingContent = {
                    Icon(
                        painter =
                            painterResource(
                                if (lockType != null) CoreR.drawable.ic_lock
                                else CoreR.drawable.ic_unlock
                            ),
                        contentDescription = null,
                    )
                },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                text = stringResource(SetupR.string.ce_profile_lock_type),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            Column(modifier = Modifier.selectableGroup()) {
                LockTypeItem(
                    type = ProfileLockType.PIN,
                    selected = lockType == ProfileLockType.PIN,
                    onAction = onAction,
                )
                LockTypeItem(
                    type = ProfileLockType.PATTERN,
                    selected = lockType == ProfileLockType.PATTERN,
                    onAction = onAction,
                )
            }
            if (lockType != null) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                PinActionItem(
                    if (lockType == ProfileLockType.PATTERN) SetupR.string.ce_profile_pattern_change
                    else SetupR.string.ce_profile_pin_change
                ) {
                    onAction(ProfilePinSettingsAction.OnChangeClick)
                }
                ListItem(
                    headlineContent = {
                        Text(stringResource(SetupR.string.ce_profile_lock_ask_on_start))
                    },
                    supportingContent = {
                        Text(stringResource(SetupR.string.ce_profile_lock_ask_on_start_summary))
                    },
                    trailingContent = { Switch(checked = state.pinOnStart, onCheckedChange = null) },
                    modifier =
                        Modifier.clickable {
                            onAction(ProfilePinSettingsAction.OnPinOnStartChange(!state.pinOnStart))
                        },
                )
                PinActionItem(
                    if (lockType == ProfileLockType.PATTERN) SetupR.string.ce_profile_pattern_remove
                    else SetupR.string.ce_profile_pin_remove,
                    destructive = true,
                ) {
                    onAction(ProfilePinSettingsAction.OnRemoveClick)
                }
            }
        }
    }

    val prompt = state.prompt
    val step = state.step
    if (prompt != null && step != null) {
        val title = stringResource(SetupR.string.ce_profile_lock)
        val pattern = prompt.lockType == ProfileLockType.PATTERN
        val message = stringResource(step.messageRes(prompt.lockType))
        val footnote =
            if (step is PinStep.Current) {
                stringResource(
                    if (pattern) SetupR.string.ce_profile_pattern_forgot
                    else SetupR.string.ce_profile_pin_forgot
                )
            } else {
                null
            }
        if (pattern) {
            PatternDialog(
                prompt = prompt,
                title = title,
                message = message,
                footnote = footnote,
                stepKey = step,
                onSubmit = { onAction(ProfilePinSettingsAction.OnPatternSubmit(it)) },
                onDismiss = { onAction(ProfilePinSettingsAction.OnPinDismiss) },
            )
        } else {
            PinDialog(
                prompt = prompt,
                title = title,
                message = message,
                footnote = footnote,
                stepKey = step,
                onSubmit = { onAction(ProfilePinSettingsAction.OnPinSubmit(it)) },
                onDismiss = { onAction(ProfilePinSettingsAction.OnPinDismiss) },
            )
        }
    }
}

/** What [this] step asks for, for a lock of [type]. */
fun PinStep.messageRes(type: ProfileLockType = ProfileLockType.PIN): Int {
    val pattern = type == ProfileLockType.PATTERN
    return when (this) {
        is PinStep.Current ->
            if (pattern) SetupR.string.ce_profile_pattern_enter_current
            else SetupR.string.ce_profile_pin_enter_current
        is PinStep.New ->
            if (pattern) SetupR.string.ce_profile_pattern_choose
            else SetupR.string.ce_profile_pin_choose
        is PinStep.Confirm ->
            if (pattern) SetupR.string.ce_profile_pattern_repeat
            else SetupR.string.ce_profile_pin_repeat
    }
}

/**
 * CE (#330): one lock type to choose. Choosing the other type than the profile's lock asks for that
 * lock first; choosing the selected one does nothing (Change PIN/pattern below does that).
 */
@Composable
private fun LockTypeItem(
    type: ProfileLockType,
    selected: Boolean,
    onAction: (ProfilePinSettingsAction) -> Unit,
) {
    val pattern = type == ProfileLockType.PATTERN
    ListItem(
        headlineContent = {
            Text(
                stringResource(
                    if (pattern) SetupR.string.ce_profile_lock_type_pattern
                    else SetupR.string.ce_profile_pin_label
                )
            )
        },
        supportingContent = {
            Text(
                stringResource(
                    if (pattern) SetupR.string.ce_profile_pattern_too_short
                    else SetupR.string.ce_profile_lock_type_pin_summary
                )
            )
        },
        leadingContent = {
            // A choice is not "alive" (VISION.md §1.2): the selected dot is onSurface, not cyan.
            RadioButton(
                selected = selected,
                onClick = null,
                colors =
                    RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.onSurface),
            )
        },
        modifier =
            Modifier.selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = {
                        if (!selected) onAction(ProfilePinSettingsAction.OnSetLockClick(type))
                    },
                )
                .testTag(if (pattern) "ce_lock_type_pattern" else "ce_lock_type_pin"),
    )
}

@Composable
private fun PinActionItem(text: Int, destructive: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(text)) },
        colors =
            if (destructive) {
                ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.error)
            } else {
                ListItemDefaults.colors()
            },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Preview
@Composable
private fun ProfilePinLayoutPreview() {
    FindroidTheme {
        ProfilePinLayout(
            state =
                ProfilePinSettingsState(
                    user = dummyUser.copy(pinHash = "x", pinSalt = "y", lockType = "pattern")
                ),
            onAction = {},
        )
    }
}
