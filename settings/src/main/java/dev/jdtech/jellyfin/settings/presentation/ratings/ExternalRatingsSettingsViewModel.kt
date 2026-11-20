package dev.jdtech.jellyfin.settings.presentation.ratings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.ExternalRatingsKeyCheck
import dev.jdtech.jellyfin.settings.domain.ExternalRatingsKeyStatus
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExternalRatingsSettingsState(
    val enabled: Boolean = false,
    val apiKey: String = "",
    /** Null until Test is pressed, and again once the switch or the key changes. */
    val test: ExternalRatingsTest? = null,
) {
    /** Test only asks MDBList while switched on: off means no request at all. */
    val canTest: Boolean
        get() = enabled && apiKey.isNotBlank() && test !is ExternalRatingsTest.Running
}

sealed interface ExternalRatingsTest {
    data object Running : ExternalRatingsTest

    data class Done(val status: ExternalRatingsKeyStatus) : ExternalRatingsTest
}

sealed interface ExternalRatingsSettingsAction {
    data class SetEnabled(val enabled: Boolean) : ExternalRatingsSettingsAction

    data class SetApiKey(val apiKey: String) : ExternalRatingsSettingsAction

    data object Test : ExternalRatingsSettingsAction
}

/**
 * CE (#331): Settings > Interface > Detail pages > External ratings: the switch, the user's MDBList
 * key and the Test button. The button is the only place an MDBList error is ever shown.
 */
@HiltViewModel
class ExternalRatingsSettingsViewModel
@Inject
constructor(
    private val appPreferences: AppPreferences,
    private val keyCheck: ExternalRatingsKeyCheck,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            ExternalRatingsSettingsState(
                enabled = appPreferences.getValue(appPreferences.externalRatings),
                apiKey = appPreferences.getValue(appPreferences.mdbListApiKey).orEmpty(),
            )
        )
    val state = _state.asStateFlow()

    private var testJob: Job? = null

    fun onAction(action: ExternalRatingsSettingsAction) {
        when (action) {
            is ExternalRatingsSettingsAction.SetEnabled -> {
                appPreferences.setValue(appPreferences.externalRatings, action.enabled)
                testJob?.cancel()
                _state.update { it.copy(enabled = action.enabled, test = null) }
            }
            is ExternalRatingsSettingsAction.SetApiKey -> {
                val key = action.apiKey.filterNot { it.isWhitespace() }
                appPreferences.setValue(appPreferences.mdbListApiKey, key.ifEmpty { null })
                testJob?.cancel()
                _state.update { it.copy(apiKey = key, test = null) }
            }
            ExternalRatingsSettingsAction.Test -> test()
        }
    }

    private fun test() {
        val current = _state.value
        if (!current.canTest) return
        _state.update { it.copy(test = ExternalRatingsTest.Running) }
        testJob = viewModelScope.launch {
            val status = keyCheck.check(current.apiKey)
            _state.update { it.copy(test = ExternalRatingsTest.Done(status)) }
        }
    }
}
