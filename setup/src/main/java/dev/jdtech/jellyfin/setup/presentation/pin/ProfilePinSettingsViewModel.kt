package dev.jdtech.jellyfin.setup.presentation.pin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.ProfileLockType
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.hasPin
import dev.jdtech.jellyfin.models.profileLockType
import dev.jdtech.jellyfin.profilelock.ProfileLock
import dev.jdtech.jellyfin.profilelock.ProfileLockEntry
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * CE (#299): which PIN the Profile PIN screen is asking for. Since #330 the same steps set a
 * pattern on phone: [Current] asks for the profile's current lock of either type, [New] and
 * [Confirm] for one of [ProfilePinSettingsState.newLockType].
 */
sealed interface PinStep {
    /**
     * The current lock, before it can be changed or replaced by the other type ([forRemoval] false)
     * or removed.
     */
    data class Current(val forRemoval: Boolean) : PinStep

    data object New : PinStep

    /** [firstEntry] is the [ProfileLockEntry.secret] of the first entry, digits or dots. */
    data class Confirm(val firstEntry: String) : PinStep
}

data class ProfilePinSettingsState(
    val user: User? = null,
    val step: PinStep? = null,
    val prompt: PinPrompt? = null,
    /** CE (#330): the type the [PinStep.New] and [PinStep.Confirm] steps are setting. */
    val newLockType: ProfileLockType = ProfileLockType.PIN,
    /**
     * CE (#330): the type of the lock the last [ProfilePinSettingsEvent] set or removed, for its
     * message.
     */
    val changedLockType: ProfileLockType = ProfileLockType.PIN,
) {
    /** Whether the profile is locked, with a PIN or a pattern. */
    val hasPin: Boolean
        get() = user?.hasPin == true

    /** CE (#330): the type of the profile's lock, or null when it has none. */
    val lockType: ProfileLockType?
        get() = user?.profileLockType

    val pinOnStart: Boolean
        get() = user?.pinOnStart != false
}

sealed interface ProfilePinSettingsAction {
    data object OnSetClick : ProfilePinSettingsAction

    /**
     * CE (#330): lock the profile with [type]. With a lock already in place (of either type) it
     * asks for that one first, so switching between PIN and pattern needs the current lock.
     */
    data class OnSetLockClick(val type: ProfileLockType) : ProfilePinSettingsAction

    data object OnChangeClick : ProfilePinSettingsAction

    data object OnRemoveClick : ProfilePinSettingsAction

    data class OnPinOnStartChange(val pinOnStart: Boolean) : ProfilePinSettingsAction

    data class OnPinSubmit(val pin: String) : ProfilePinSettingsAction

    /** CE (#330): a pattern drawn in any step. */
    data class OnPatternSubmit(val dots: List<Int>) : ProfilePinSettingsAction

    data object OnPinDismiss : ProfilePinSettingsAction

    data object OnBackClick : ProfilePinSettingsAction
}

sealed interface ProfilePinSettingsEvent {
    data object PinSet : ProfilePinSettingsEvent

    data object PinRemoved : ProfilePinSettingsEvent
}

/**
 * CE (#299): Settings → Profile PIN for the signed-in profile. A new PIN is typed twice; changing
 * or removing it asks for the current one first.
 *
 * CE (#330): on phone the screen is Profile lock, and the lock can be a pattern, drawn twice. A
 * profile has one lock: setting the other type replaces it, after the current one.
 */
@HiltViewModel
class ProfilePinSettingsViewModel
@Inject
constructor(private val repository: SetupRepository, private val profileLock: ProfileLock) :
    ViewModel() {
    private val _state = MutableStateFlow(ProfilePinSettingsState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<ProfilePinSettingsEvent>()
    val events = eventsChannel.receiveAsFlow()

    init {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val user = repository.getCurrentUser()
        _state.update { it.copy(user = user) }
    }

    fun onAction(action: ProfilePinSettingsAction) {
        when (action) {
            is ProfilePinSettingsAction.OnSetClick -> setLock(ProfileLockType.PIN)
            is ProfilePinSettingsAction.OnSetLockClick -> setLock(action.type)
            is ProfilePinSettingsAction.OnChangeClick ->
                setLock(_state.value.lockType ?: ProfileLockType.PIN)
            is ProfilePinSettingsAction.OnRemoveClick -> ask(PinStep.Current(forRemoval = true))
            is ProfilePinSettingsAction.OnPinOnStartChange -> {
                val user = _state.value.user ?: return
                viewModelScope.launch {
                    profileLock.setPinOnStart(user.id, action.pinOnStart)
                    reload()
                }
            }
            is ProfilePinSettingsAction.OnPinSubmit -> submit(ProfileLockEntry.Pin(action.pin))
            is ProfilePinSettingsAction.OnPatternSubmit ->
                submit(ProfileLockEntry.Pattern(action.dots))
            is ProfilePinSettingsAction.OnPinDismiss ->
                _state.update { it.copy(step = null, prompt = null) }
            is ProfilePinSettingsAction.OnBackClick -> Unit
        }
    }

    /** A new lock of [type]: straight away without a lock, after the current one with one. */
    private fun setLock(type: ProfileLockType) {
        _state.update { it.copy(newLockType = type) }
        ask(if (_state.value.hasPin) PinStep.Current(forRemoval = false) else PinStep.New)
    }

    private fun ask(step: PinStep, error: PinError? = null) {
        val user = _state.value.user ?: return
        val previous = _state.value.prompt
        val lockType = lockTypeFor(step)
        val prompt =
            if (error != null && previous != null)
                previous.rejected(error).copy(lockType = lockType)
            else PinPrompt(user.id, user.name, lockType = lockType)
        _state.update { it.copy(step = step, prompt = prompt) }
    }

    /** What [step] asks for: the current lock's type, or the type being set. */
    private fun lockTypeFor(step: PinStep): ProfileLockType =
        when (step) {
            is PinStep.Current -> _state.value.lockType ?: ProfileLockType.PIN
            else -> _state.value.newLockType
        }

    private fun submit(entry: ProfileLockEntry) {
        val user = _state.value.user ?: return
        val step = _state.value.step ?: return
        viewModelScope.launch {
            when (step) {
                is PinStep.Current -> {
                    if (!profileLock.verify(user.id, entry)) {
                        _state.update { it.copy(prompt = it.prompt?.rejected(PinError.WRONG)) }
                    } else if (step.forRemoval) {
                        val removed = _state.value.lockType ?: ProfileLockType.PIN
                        profileLock.clearPin(user.id)
                        _state.update {
                            it.copy(step = null, prompt = null, changedLockType = removed)
                        }
                        reload()
                        eventsChannel.send(ProfilePinSettingsEvent.PinRemoved)
                    } else {
                        ask(PinStep.New)
                    }
                }
                is PinStep.New -> {
                    if (entry.type != _state.value.newLockType || !entry.isValid) return@launch
                    ask(PinStep.Confirm(entry.secret))
                }
                is PinStep.Confirm -> {
                    if (entry.type != _state.value.newLockType || entry.secret != step.firstEntry) {
                        ask(PinStep.New, PinError.MISMATCH)
                    } else {
                        when (entry) {
                            is ProfileLockEntry.Pin -> profileLock.setPin(user.id, entry.pin)
                            is ProfileLockEntry.Pattern ->
                                profileLock.setPattern(user.id, entry.dots)
                        }
                        _state.update {
                            it.copy(step = null, prompt = null, changedLockType = entry.type)
                        }
                        reload()
                        eventsChannel.send(ProfilePinSettingsEvent.PinSet)
                    }
                }
            }
        }
    }
}
