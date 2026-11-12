package dev.jdtech.jellyfin.setup.presentation.localnetwork

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.setup.domain.LocalNetworkBlockState
import dev.jdtech.jellyfin.setup.domain.ServerLocalNetworkBlock
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * CE (#571): [ServerLocalNetworkBlock] for the activity, which hands its state to the error
 * components and asks again on resume and whenever an error appears.
 */
@HiltViewModel
class LocalNetworkBlockViewModel @Inject constructor(private val block: ServerLocalNetworkBlock) :
    ViewModel() {
    val state: StateFlow<LocalNetworkBlockState> = block.state

    fun refresh() {
        viewModelScope.launch { block.refresh() }
    }
}
