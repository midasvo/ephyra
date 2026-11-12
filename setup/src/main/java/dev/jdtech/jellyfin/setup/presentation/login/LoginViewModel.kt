package dev.jdtech.jellyfin.setup.presentation.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.domain.ServerLocalNetworkBlock
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.utils.LoadErrorText
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.exception.InvalidStatusException

@HiltViewModel
class LoginViewModel
@Inject
constructor(
    private val repository: SetupRepository,
    private val localNetworkBlock: ServerLocalNetworkBlock,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<LoginEvent>()
    val events = eventsChannel.receiveAsFlow()

    private var quickConnectJob: Job? = null

    fun loadServer() {
        viewModelScope.launch {
            try {
                val server = repository.getCurrentServer()
                _state.emit(_state.value.copy(serverName = server?.name))
            } catch (e: Exception) { Timber.e(e, "Failed to load server") }
        }
    }

    fun loadDisclaimer() {
        viewModelScope.launch {
            try {
                val loginDisclaimer = repository.loadDisclaimer()
                _state.emit(_state.value.copy(disclaimer = loginDisclaimerText(loginDisclaimer)))
            } catch (e: Exception) { Timber.e(e, "Failed to load disclaimer") }
        }
    }

    fun loadQuickConnectEnabled() {
        viewModelScope.launch {
            try {
                val isEnabled = repository.getIsQuickConnectEnabled()
                _state.emit(_state.value.copy(quickConnectEnabled = isEnabled))
            } catch (e: Exception) { Timber.e(e, "Failed to load quick connect status") }
        }
    }

    private fun login(username: String, password: String) {
        viewModelScope.launch {
            try {
                _state.emit(_state.value.copy(isLoading = true, error = null))
                repository.login(username, password)
                _state.emit(_state.value.copy(isLoading = false))
                eventsChannel.send(LoginEvent.Success)
            } catch (e: Exception) {
                val message =
                    if (e is InvalidStatusException && e.status == 401) {
                        UiText.StringResource(SetupR.string.login_error_wrong_username_password)
                    } else {
                        connectionErrorText(e) ?: UiText.StringResource(CoreR.string.unknown_error)
                    }
                _state.emit(_state.value.copy(isLoading = false, error = message))
            }
        }
    }

    private fun quickConnect() {
        if (quickConnectJob?.isActive == true) {
            quickConnectJob?.cancel()
            quickConnectJob = null
            // CE (#382): the code goes with the polling, so the button never shows a dead code.
            _state.update { it.copy(quickConnectCode = null) }
            return
        }
        quickConnectJob =
            viewModelScope.launch {
                var step = QuickConnectStep.INITIATE
                try {
                    var quickConnectState = repository.initiateQuickConnect()
                    // CE (#606): a new code replaces the error of the previous attempt.
                    _state.emit(
                        _state.value.copy(quickConnectCode = quickConnectState.code, error = null)
                    )

                    step = QuickConnectStep.POLL
                    while (!quickConnectState.authenticated) {
                        delay(5000L)
                        quickConnectState =
                            repository.getQuickConnectState(quickConnectState.secret)
                    }

                    step = QuickConnectStep.LOGIN
                    repository.loginWithSecret(quickConnectState.secret)

                    _state.emit(_state.value.copy(quickConnectCode = null))
                    eventsChannel.send(LoginEvent.Success)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Quick connect failed")
                    // CE (#606): plain words, never the exception's own text.
                    val message = connectionErrorText(e) ?: quickConnectErrorText(e, step)
                    _state.update {
                        it.copy(
                            quickConnectCode = null,
                            error = message,
                            // Turned off on the server: the button would only fail again.
                            quickConnectEnabled =
                                it.quickConnectEnabled && !isQuickConnectOff(e, step),
                        )
                    }
                }
            }
    }

    /**
     * CE (#571): a server that could not be reached says so instead of "Unknown error", and names
     * the local network permission when that is what blocks it. Null for any other failure.
     */
    private suspend fun connectionErrorText(e: Throwable): UiText? =
        when {
            !LoadErrorText.isConnectionError(e) -> null
            localNetworkBlock.refresh() ->
                UiText.StringResource(SetupR.string.ce_add_server_error_local_network)
            else -> UiText.StringResource(CoreR.string.download_error_server_unreachable)
        }

    fun onAction(action: LoginAction) {
        when (action) {
            is LoginAction.OnLoginClick -> {
                // CE (#516): a space around the name, easy to add with an on-screen keyboard,
                // failed the login on TV. The password stays as typed.
                login(action.username.trim(), action.password)
            }
            is LoginAction.OnQuickConnectClick -> {
                quickConnect()
            }
            else -> Unit
        }
    }
}
