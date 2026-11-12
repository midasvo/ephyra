package dev.jdtech.jellyfin.player.cast.presentation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.player.cast.CastPlayerController
import dev.jdtech.jellyfin.player.cast.CastSessionManager
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.models.CastError
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class CastSessionViewModel
@Inject
constructor(
    val sessionManager: CastSessionManager,
    private val playerController: CastPlayerController,
) : ViewModel() {

    init {
        // Registers the session listeners; without them no connection is ever seen. It does
        // nothing a second time, and nothing at all in a build without Cast.
        sessionManager.init()
    }

    val connectionState: StateFlow<CastConnectionState> = sessionManager.connectionState

    val errors: Flow<CastError> = playerController.errors

    fun playItem(
        itemId: UUID,
        itemKind: String,
        startFromBeginning: Boolean,
        mediaSourceId: String? = null,
    ) {
        playerController.playItem(itemId, itemKind, startFromBeginning, mediaSourceId)
    }
}
