package dev.jdtech.jellyfin.setup.presentation.login

import androidx.compose.ui.text.AnnotatedString
import dev.jdtech.jellyfin.models.UiText

data class LoginState(
    val serverName: String? = null,
    // CE (#375): the server's HTML disclaimer as styled text, see loginDisclaimerText.
    val disclaimer: AnnotatedString? = null,
    val quickConnectEnabled: Boolean = false,
    val quickConnectCode: String? = null,
    val isLoading: Boolean = false,
    val error: UiText? = null,
)
