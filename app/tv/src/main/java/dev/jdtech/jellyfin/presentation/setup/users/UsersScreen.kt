package dev.jdtech.jellyfin.presentation.setup.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.ce.presentation.pin.TvSwitchPinDialog
import dev.jdtech.jellyfin.ce.ui.focus.ephyraButtonBorder
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.presentation.setup.components.UserItem
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.users.UsersAction
import dev.jdtech.jellyfin.setup.presentation.users.UsersEvent
import dev.jdtech.jellyfin.setup.presentation.users.UsersState
import dev.jdtech.jellyfin.setup.presentation.users.UsersViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import java.util.UUID

@Composable
fun UsersScreen(
    navigateToHome: () -> Unit,
    onChangeServerClick: () -> Unit,
    onAddClick: () -> Unit,
    onPublicUserClick: (String) -> Unit,
    viewModel: UsersViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val api = JellyfinApi.getInstance(context)
    val state by viewModel.state.collectAsState()

    LaunchedEffect(true) { viewModel.loadUsers() }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is UsersEvent.NavigateToHome -> navigateToHome()
        }
    }

    UsersScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is UsersAction.OnChangeServerClick -> onChangeServerClick()
                is UsersAction.OnAddClick -> onAddClick()
                is UsersAction.OnPublicUserClick -> onPublicUserClick(action.username)
                else -> Unit
            }
            viewModel.onAction(action)
        },
        baseUrl = api.api.baseUrl ?: "",
    )
}

@Composable
internal fun UsersScreenLayout(
    state: UsersState,
    onAction: (UsersAction) -> Unit,
    baseUrl: String,
) {
    val userRequesters = remember { mutableMapOf<UUID, FocusRequester>() }
    val addRequester = remember { FocusRequester() }
    // CE (#541): the element that opened another screen, focused again on the way back.
    val serverRequester = remember { FocusRequester() }
    val publicRequesters = remember { mutableMapOf<UUID, FocusRequester>() }
    var opener by rememberSaveable { mutableStateOf<String?>(null) }
    // The saved user that had focus last: what "Remove user" acts on.
    var focusedUserId by remember { mutableStateOf<UUID?>(null) }
    var removing by remember { mutableStateOf<User?>(null) }
    val hasUsers = state.users.isNotEmpty() || state.publicUsers.isNotEmpty()
    val focusedUser = state.users.firstOrNull { it.id == focusedUserId }

    // Focus starts on the signed-in user, else the first saved user, else "Add user". CE (#541):
    // after Back, on what was opened; with no users at all, on "Add user".
    LaunchedEffect(hasUsers, state.users.isNotEmpty(), state.currentUserId) {
        val target =
            state.users.firstOrNull { it.id == state.currentUserId } ?: state.users.firstOrNull()
        repeat(10) {
            withFrameNanos {}
            val requester =
                openerRequester(opener, addRequester, serverRequester, publicRequesters)
                    ?: target?.let { userRequesters[it.id] }
                    ?: addRequester
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
        ) {
            Text(
                text = stringResource(id = SetupR.string.users),
                style = MaterialTheme.typography.displayMedium,
            )
            Text(
                text = state.serverName.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!hasUsers) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))
                Text(
                    text = stringResource(id = SetupR.string.users_no_users),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))
            } else {
                // Padding, not clipping: the 1.06 scale, the ring and the 20 dp halo reach past the
                // avatars and the row must not cut them off.
                LazyRow(
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            MaterialTheme.spacings.default,
                            Alignment.CenterHorizontally,
                        ),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(state.users, key = { it.id }) { user ->
                        UserItem(
                            user = user,
                            baseUrl = baseUrl,
                            current = user.id == state.currentUserId,
                            onClick = { onAction(UsersAction.OnUserClick(user.id)) },
                            onLongClick = { removing = user },
                            onFocused = { focusedUserId = it.id },
                            focusRequester = userRequesters.getOrPut(user.id) { FocusRequester() },
                        )
                    }
                    if (state.users.isNotEmpty() && state.publicUsers.isNotEmpty()) {
                        item { Spacer(modifier = Modifier.width(MaterialTheme.spacings.large)) }
                    }
                    items(state.publicUsers, key = { "public-${it.id}" }) { user ->
                        UserItem(
                            user = user,
                            baseUrl = baseUrl,
                            caption = stringResource(SetupR.string.ce_users_sign_in),
                            onClick = {
                                opener = OPENER_PUBLIC + user.id // CE (#541)
                                onAction(UsersAction.OnPublicUserClick(username = it.name))
                            },
                            // CE (#541)
                            focusRequester =
                                publicRequesters.getOrPut(user.id) { FocusRequester() },
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                modifier = Modifier.padding(bottom = 24.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        opener = OPENER_ADD // CE (#541)
                        onAction(UsersAction.OnAddClick)
                    },
                    border = ephyraButtonBorder(),
                    modifier = Modifier.focusRequester(addRequester).testTag(USERS_ADD_TAG),
                ) {
                    Text(text = stringResource(id = SetupR.string.users_btn_add_user))
                }
                OutlinedButton(
                    onClick = {
                        opener = OPENER_SERVER // CE (#541)
                        onAction(UsersAction.OnChangeServerClick)
                    },
                    border = ephyraButtonBorder(),
                    modifier = Modifier.focusRequester(serverRequester).testTag(USERS_SERVER_TAG),
                ) {
                    Text(text = stringResource(id = SetupR.string.ce_users_switch_server))
                }
                OutlinedButton(
                    onClick = { removing = focusedUser },
                    enabled = focusedUser != null,
                    border = ephyraButtonBorder(),
                ) {
                    Text(text = stringResource(id = SetupR.string.remove_user_dialog))
                }
            }
        }
    }

    // CE (#299)
    TvSwitchPinDialog(state = state, onAction = onAction)

    removing?.let { user ->
        RemoveUserDialog(
            userName = user.name,
            onConfirm = {
                removing = null
                if (focusedUserId == user.id) focusedUserId = null
                onAction(UsersAction.OnDeleteUser(user.id))
            },
            onDismiss = { removing = null },
        )
    }
}

// CE (#541)
internal const val USERS_ADD_TAG = "users_add"
internal const val USERS_SERVER_TAG = "users_switch_server"
private const val OPENER_ADD = "add"
private const val OPENER_SERVER = "server"
private const val OPENER_PUBLIC = "public:"

/** CE (#541): the element that opened another screen, or null to use the default focus. */
private fun openerRequester(
    opener: String?,
    addRequester: FocusRequester,
    serverRequester: FocusRequester,
    publicRequesters: Map<UUID, FocusRequester>,
): FocusRequester? =
    when {
        opener == OPENER_ADD -> addRequester
        opener == OPENER_SERVER -> serverRequester
        opener?.startsWith(OPENER_PUBLIC) == true ->
            publicRequesters.entries
                .firstOrNull { it.key.toString() == opener.removePrefix(OPENER_PUBLIC) }
                ?.value
        else -> null
    }

@Composable
private fun RemoveUserDialog(userName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelRequester = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(MaterialTheme.spacings.default)) {
                Text(
                    text = stringResource(SetupR.string.remove_user_dialog),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
                Text(
                    text = stringResource(SetupR.string.remove_user_dialog_text, userName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.default))
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        border = ephyraButtonBorder(),
                        modifier = Modifier.focusRequester(cancelRequester),
                    ) {
                        Text(text = stringResource(SetupR.string.cancel))
                    }
                    Button(onClick = onConfirm) {
                        Text(text = stringResource(SetupR.string.confirm))
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { cancelRequester.requestFocus() }
}

private val previewUsers =
    listOf(
        dummyUser.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000001"), name = "Midas"),
        dummyUser.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000002"), name = "Kiddos"),
    )

@Preview(device = "id:tv_1080p")
@Composable
private fun UsersScreenLayoutPreview() {
    FindroidTheme {
        UsersScreenLayout(
            state =
                UsersState(
                    users = previewUsers,
                    publicUsers =
                        listOf(
                            dummyUser.copy(
                                id = UUID.fromString("00000000-0000-0000-0000-000000000003"),
                                name = "Guest",
                            )
                        ),
                    serverName = "Demo",
                    currentUserId = previewUsers.first().id,
                ),
            onAction = {},
            baseUrl = "https://demo.jellyfin.org/stable",
        )
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun UsersScreenLayoutPreviewNoUsers() {
    FindroidTheme {
        UsersScreenLayout(
            state = UsersState(serverName = "Demo"),
            onAction = {},
            baseUrl = "https://demo.jellyfin.org/stable",
        )
    }
}
