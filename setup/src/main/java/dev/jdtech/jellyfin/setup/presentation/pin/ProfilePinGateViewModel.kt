package dev.jdtech.jellyfin.setup.presentation.pin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.ProfileLockType
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.profileLockType
import dev.jdtech.jellyfin.profilelock.ProfileLock
import dev.jdtech.jellyfin.profilelock.ProfileLockEntry
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfilePinGateState(val user: User? = null, val prompt: PinPrompt? = null)

sealed interface ProfilePinGateAction {
    data class OnPinSubmit(val pin: String) : ProfilePinGateAction

    /** CE (#330): a pattern drawn on the grid, for a profile locked with one. */
    data class OnPatternSubmit(val dots: List<Int>) : ProfilePinGateAction

    /** Back or "Other profile": leave the profile closed and pick another one. */
    data object OnCancel : ProfilePinGateAction
}

/**
 * CE (#299): the PIN asked when the app starts in a profile that has one, before Home loads. A
 * correct PIN unlocks the profile, and the app's main state lets Home through. Cancelling closes
 * the profile (the server keeps no current user), so the app shows the user selection instead of
 * exiting.
 */
@HiltViewModel
class ProfilePinGateViewModel
@Inject
constructor(private val repository: SetupRepository, private val profileLock: ProfileLock) :
    ViewModel() {
    private val _state = MutableStateFlow(ProfilePinGateState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val user = repository.getCurrentUser() ?: return@launch
            val prompt =
                PinPrompt(
                    user.id,
                    user.name,
                    lockType = user.profileLockType ?: ProfileLockType.PIN,
                )
            _state.update { ProfilePinGateState(user = user, prompt = prompt) }
        }
    }

    fun onAction(action: ProfilePinGateAction) {
        when (action) {
            is ProfilePinGateAction.OnPinSubmit -> submit(ProfileLockEntry.Pin(action.pin))
            is ProfilePinGateAction.OnPatternSubmit -> submit(ProfileLockEntry.Pattern(action.dots))
            is ProfilePinGateAction.OnCancel ->
                viewModelScope.launch { repository.clearCurrentUser() }
        }
    }

    private fun submit(entry: ProfileLockEntry) {
        val prompt = _state.value.prompt ?: return
        viewModelScope.launch {
            if (profileLock.verify(prompt.userId, entry)) {
                profileLock.unlock(prompt.userId)
            } else {
                _state.update { it.copy(prompt = prompt.rejected(PinError.WRONG)) }
            }
        }
    }
}
