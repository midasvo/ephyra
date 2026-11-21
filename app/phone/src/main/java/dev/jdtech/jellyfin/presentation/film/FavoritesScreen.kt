package dev.jdtech.jellyfin.presentation.film

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.film.presentation.collection.CollectionAction
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.film.presentation.favorites.FavoritesViewModel
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.LocalServerStatus
import dev.jdtech.jellyfin.presentation.utils.ReloadAfterSync
import dev.jdtech.jellyfin.presentation.utils.ReloadWhenServerAwayChanges

@Composable
fun FavoritesScreen(
    onItemClick: (item: FindroidItem) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // CE (#775): the downloaded favourites while the server is away, all of them once it is back.
    ReloadWhenServerAwayChanges(viewModel::onServerAwayChanged)
    // Loads on entry, as always. CE (#782): and on a later resume when the list is out of date, as
    // after a sync that finished while the app was in the background, which ReloadAfterSync
    // missed. The state of the server is told first, so a resume after a change loads once.
    val serverAway = LocalServerStatus.current.unreachable
    var entered by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onServerAwayChanged(serverAway)
        if (!entered || viewModel.shouldReload()) viewModel.loadItems()
        entered = true
    }
    // CE (#775): a favourite set while the server was away reaches it with the sync, which can be
    // after the reload above for the server's return.
    ReloadAfterSync(viewModel::onSynced)

    CollectionScreenLayout(
        collectionName = stringResource(CoreR.string.title_favorite),
        state = state,
        onAction = { action ->
            when (action) {
                is CollectionAction.OnItemClick -> onItemClick(action.item)
                is CollectionAction.OnRetryClick -> viewModel.loadItems()
                is CollectionAction.OnBackClick -> Unit
            }
        },
        emptyIcon = CoreR.drawable.ic_heart,
        emptyTitle = stringResource(CoreR.string.no_favorites),
        emptyBody = stringResource(CoreR.string.favorites_empty_body),
        showNavigationIcon = false,
        serverBanner = true,
        downloadsOnlyEmptyTitle = stringResource(CoreR.string.ce_favorites_none_downloaded),
        downloadsOnlyEmptyBody = stringResource(CoreR.string.ce_favorites_none_downloaded_body),
    )
}

@PreviewScreenSizes
@Composable
private fun CollectionScreenLayoutPreview() {
    FindroidTheme {
        CollectionScreenLayout(
            collectionName = "Favorites",
            state =
                CollectionState(
                    sections =
                        listOf(
                            CollectionSection(
                                id = 0,
                                name = UiText.StringResource(CoreR.string.title_favorite),
                                items = dummyMovies,
                            )
                        )
                ),
            onAction = {},
        )
    }
}
