package dev.jdtech.jellyfin.presentation.setup.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.ce.ui.TvSafeArea
import dev.jdtech.jellyfin.ce.ui.rememberTvTextFieldEditing
import dev.jdtech.jellyfin.ce.ui.tvTextFieldEditing
import dev.jdtech.jellyfin.ce.ui.focus.ephyraButtonBorder
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.setup.presentation.login.LoginAction
import dev.jdtech.jellyfin.setup.presentation.login.LoginEvent
import dev.jdtech.jellyfin.setup.presentation.login.LoginState
import dev.jdtech.jellyfin.setup.presentation.login.LoginViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onSuccess: () -> Unit,
    onChangeServerClick: () -> Unit,
    prefilledUsername: String? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(true) {
        viewModel.loadServer()
        viewModel.loadDisclaimer()
        viewModel.loadQuickConnectEnabled()
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is LoginEvent.Success -> onSuccess()
        }
    }

    LoginScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is LoginAction.OnChangeServerClick -> onChangeServerClick()
                else -> Unit
            }
            viewModel.onAction(action)
        },
        prefilledUsername = prefilledUsername,
    )
}

// CE (#557): internal for LoginServerButtonFocusTest.
@Composable
internal fun LoginScreenLayout(
    state: LoginState,
    onAction: (LoginAction) -> Unit,
    prefilledUsername: String? = null,
) {
    var username by rememberSaveable { mutableStateOf(prefilledUsername ?: "") }
    var password by rememberSaveable { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    // CE (#557): no direction finds the form from the server button (the scrolling form is one
    // focus group as tall and wide as the screen), so Down and Left go to the form by hand and the
    // form puts focus back on the element that was left (Username the first time).
    val formRequester = remember { FocusRequester() }
    val doLogin = { onAction(LoginAction.OnLoginClick(username, password)) }
    // CE: the keyboard opens on OK, not when focus moves onto a field.
    val usernameEditing = rememberTvTextFieldEditing()
    val passwordEditing = rememberTvTextFieldEditing()

    // CE (#423): everything inside the TV safe area. The form scrolls when it doesn't fit (a long
    // server disclaimer, a large font), and the last button scrolls to the end, so the disclaimer
    // below it can be read.
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollToEndOnFocus =
        Modifier.onFocusChanged {
            if (it.isFocused) scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
        }

    Box(modifier = Modifier.fillMaxSize().padding(TvSafeArea.paddingValues)) {
        IconButton(
            onClick = { onAction(LoginAction.OnChangeServerClick) },
            border = ephyraButtonBorder(), // CE (#540)
            // CE (#540): room for the focus scale and the ring inside the safe area.
            modifier =
                Modifier.align(Alignment.TopEnd)
                    .padding(6.dp)
                    // CE (#557)
                    .focusProperties {
                        down = formRequester
                        left = formRequester
                    }
                    .testTag(LOGIN_SERVER_BUTTON_TAG),
        ) {
            Icon(painter = painterResource(R.drawable.ic_server), contentDescription = null)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.fillMaxWidth()
                    .align(Alignment.Center)
                    .verticalScroll(scrollState)
                    // CE (#557): inside the scroll's own focus group, so the restorer's children
                    // are the fields and buttons.
                    .focusRequester(formRequester)
                    .focusRestorer(focusRequester)
                    .focusGroup(),
        ) {
            Text(
                text = stringResource(id = dev.jdtech.jellyfin.setup.R.string.login),
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
            Text(
                text =
                    stringResource(
                        dev.jdtech.jellyfin.setup.R.string.server_subtitle,
                        state.serverName ?: "",
                    ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.default))
            OutlinedTextField(
                value = username,
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_user),
                        contentDescription = null,
                    )
                },
                onValueChange = { username = it },
                label = {
                    Text(
                        text =
                            stringResource(
                                id = dev.jdtech.jellyfin.setup.R.string.edit_text_username_hint
                            )
                    )
                },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                // CE: Next keeps the keyboard up on the password.
                keyboardActions =
                    KeyboardActions(
                        onNext = {
                            passwordEditing.startEditing()
                            defaultKeyboardAction(ImeAction.Next)
                        }
                    ),
                readOnly = !usernameEditing.editing, // CE
                isError = state.error != null,
                enabled = !state.isLoading,
                modifier =
                    Modifier.width(360.dp)
                        .focusRequester(focusRequester)
                        .tvTextFieldEditing(usernameEditing), // CE
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
            OutlinedTextField(
                value = password,
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lock),
                        contentDescription = null,
                    )
                },
                onValueChange = { password = it },
                label = {
                    Text(
                        text =
                            stringResource(
                                id = dev.jdtech.jellyfin.setup.R.string.edit_text_password_hint
                            )
                    )
                },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                keyboardActions = KeyboardActions(onGo = { doLogin() }),
                visualTransformation = PasswordVisualTransformation(),
                readOnly = !passwordEditing.editing, // CE
                isError = state.error != null,
                enabled = !state.isLoading,
                supportingText = {
                    if (state.error != null) {
                        Text(
                            text = state.error!!.asString(),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                modifier = Modifier.width(360.dp).tvTextFieldEditing(passwordEditing), // CE
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.default))
            Box {
                Button(
                    onClick = { doLogin() },
                    enabled = !state.isLoading,
                    border = ephyraButtonBorder(), // CE (#540)
                    modifier =
                        Modifier.width(360.dp)
                            .then(if (state.quickConnectEnabled) Modifier else scrollToEndOnFocus),
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                color = LocalContentColor.current,
                                modifier = Modifier.size(24.dp).align(Alignment.CenterStart),
                            )
                        }
                        Text(
                            text =
                                stringResource(
                                    id = dev.jdtech.jellyfin.setup.R.string.login_btn_login
                                ),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
            AnimatedVisibility(state.quickConnectEnabled) {
                Column {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.width(360.dp),
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        )
                        Text(
                            text = stringResource(dev.jdtech.jellyfin.setup.R.string.or),
                            color = DividerDefaults.color,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        HorizontalDivider(
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
                    Box {
                        OutlinedButton(
                            onClick = { onAction(LoginAction.OnQuickConnectClick) },
                            border = ephyraButtonBorder(), // CE (#540)
                            modifier = Modifier.width(360.dp).then(scrollToEndOnFocus),
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (state.quickConnectCode != null) {
                                    CircularProgressIndicator(
                                        color = LocalContentColor.current,
                                        modifier =
                                            Modifier.size(24.dp).align(Alignment.CenterStart),
                                    )
                                }
                                Text(
                                    text =
                                        if (state.quickConnectCode != null) {
                                            state.quickConnectCode!!
                                        } else {
                                            stringResource(
                                                dev.jdtech.jellyfin.setup.R.string
                                                    .login_btn_quick_connect
                                            )
                                        },
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        }
                    }
                }
            }
            if (state.disclaimer != null) {
                Text(
                    text = state.disclaimer!!,
                    modifier = Modifier.padding(MaterialTheme.spacings.default),
                )
            }
        }
    }

    LaunchedEffect(true) { focusRequester.requestFocus() }
}

internal const val LOGIN_SERVER_BUTTON_TAG = "ce_login_server_button" // CE (#557)

@Preview(device = "id:tv_1080p")
@Composable
private fun LoginScreenLayoutPreview() {
    FindroidTheme {
        LoginScreenLayout(
            state = LoginState(serverName = "Demo Server", quickConnectEnabled = true),
            onAction = {},
        )
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun LoginScreenLayoutPreviewError() {
    FindroidTheme {
        LoginScreenLayout(
            state =
                LoginState(
                    serverName = "Demo Server",
                    error = UiText.DynamicString("Invalid username or password"),
                ),
            onAction = {},
        )
    }
}
