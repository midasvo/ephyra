package dev.jdtech.jellyfin.setup.presentation.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.ProfileLockType
import dev.jdtech.jellyfin.profilelock.ProfileLock
import dev.jdtech.jellyfin.profilelock.ProfileLockEntry
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.presentation.pin.PinError
import dev.jdtech.jellyfin.setup.presentation.pin.PinPrompt
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class UsersViewModel
@Inject
constructor(private val repository: SetupRepository, private val profileLock: ProfileLock) :
    ViewModel() {
    private val _state = MutableStateFlow(UsersState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<UsersEvent>()
    val events = eventsChannel.receiveAsFlow()

    fun loadUsers() {
        viewModelScope.launch {
            val server = repository.getCurrentServer() ?: return@launch
            val users = repository.getUsers(server.id)
            val userIds = users.map { it.id }

            _state.emit(
                UsersState(
                    users = users,
                    serverName = server.name,
                    currentUserId = server.currentUserId,
                    pinPrompt = _state.value.pinPrompt,
                )
            )

            try {
                val publicUsers = repository.getPublicUsers(server.id)
                // Pick up changed profile pictures of the saved users.
                val publicById = publicUsers.associateBy { it.id }
                val refreshed = users.map { user ->
                    val tag = publicById[user.id]?.primaryImageTag
                    if (publicById.containsKey(user.id) && tag != user.primaryImageTag) {
                        repository.updateUserImageTag(user.id, tag)
                        user.copy(primaryImageTag = tag)
                    } else {
                        user
                    }
                }
                _state.emit(
                    _state.value.copy(
                        users = refreshed,
                        publicUsers = publicUsers.filterNot { userIds.contains(it.id) },
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load public users")
            }
        }
    }

    /**
     * Delete user from database
     *
     * @param userId The id of the user
     */
    private fun deleteUser(userId: UUID) {
        viewModelScope.launch {
            repository.deleteUser(userId)
            loadUsers()
        }
    }

    private fun loginAsUser(userId: UUID) {
        viewModelScope.launch {
            // CE (#299): a profile with a PIN opens only after the PIN.
            if (profileLock.needsPinToSwitch(userId)) {
                val user = _state.value.users.firstOrNull { it.id == userId }
                val prompt =
                    PinPrompt(
                        userId = userId,
                        userName = user?.name.orEmpty(),
                        // CE (#330): the grid for a pattern lock.
                        lockType = profileLock.lockType(userId) ?: ProfileLockType.PIN,
                    )
                _state.update { it.copy(pinPrompt = prompt) }
                return@launch
            }
            switchTo(userId)
        }
    }

    private suspend fun switchTo(userId: UUID) {
        // Unlocked before it becomes current, so the start lock never shows for it.
        profileLock.unlock(userId)
        repository.setCurrentUser(userId)

        eventsChannel.send(UsersEvent.NavigateToHome)
    }

    private fun submitPin(entry: ProfileLockEntry) {
        val prompt = _state.value.pinPrompt ?: return
        viewModelScope.launch {
            if (profileLock.verify(prompt.userId, entry)) {
                _state.update { it.copy(pinPrompt = null) }
                switchTo(prompt.userId)
            } else {
                _state.update { it.copy(pinPrompt = prompt.rejected(PinError.WRONG)) }
            }
        }
    }

    fun onAction(action: UsersAction) {
        when (action) {
            is UsersAction.OnUserClick -> {
                loginAsUser(action.userId)
            }
            is UsersAction.OnDeleteUser -> {
                deleteUser(action.userId)
            }
            is UsersAction.OnPinSubmit -> submitPin(ProfileLockEntry.Pin(action.pin))
            is UsersAction.OnPatternSubmit -> submitPin(ProfileLockEntry.Pattern(action.dots))
            // Cancelled: stay on the profile that was open.
            is UsersAction.OnPinDismiss -> _state.update { it.copy(pinPrompt = null) }
            else -> Unit
        }
    }
}
