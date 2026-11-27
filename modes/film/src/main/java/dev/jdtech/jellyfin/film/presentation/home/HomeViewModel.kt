package dev.jdtech.jellyfin.film.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.R as FilmR
import dev.jdtech.jellyfin.film.domain.homerows.HomeRowConfig
import dev.jdtech.jellyfin.film.domain.homerows.HomeRowType
import dev.jdtech.jellyfin.film.domain.homerows.HomeRows
import dev.jdtech.jellyfin.film.domain.homerows.HomeRowsProfile
import dev.jdtech.jellyfin.film.domain.homerows.HomeRowsStore
import dev.jdtech.jellyfin.film.domain.homerows.LegacyHomeSwitches
import dev.jdtech.jellyfin.film.domain.homerows.homeLibraries
import dev.jdtech.jellyfin.film.presentation.ServerAwayReload
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.ItemLists
import dev.jdtech.jellyfin.repository.ReloadPolicy
import dev.jdtech.jellyfin.repository.StoredServers
import dev.jdtech.jellyfin.repository.StoredUsers
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.toView
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

@HiltViewModel
class HomeViewModel
internal constructor(
    private val lists: ItemLists,
    private val catalog: ItemCatalog,
    private val account: CurrentAccount,
    private val appPreferences: AppPreferences,
    private val storedServers: StoredServers,
    private val storedUsers: StoredUsers,
    private val dataChanges: DataChangeEvents = DataChangeEvents(),
    private val homeCache: HomeCache? = null,
    /**
     * CE (#809): where a load runs; the tests run it on their own dispatcher, so no load outlives
     * its test.
     */
    private val loadDispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        lists: ItemLists,
        catalog: ItemCatalog,
        account: CurrentAccount,
        appPreferences: AppPreferences,
        storedServers: StoredServers,
        storedUsers: StoredUsers,
        dataChanges: DataChangeEvents = DataChangeEvents(),
        homeCache: HomeCache? = null,
    ) : this(
        lists,
        catalog,
        account,
        appPreferences,
        storedServers,
        storedUsers,
        dataChanges,
        homeCache,
        Dispatchers.Default,
    )

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()

    private val uiTextContinueWatching = UiText.StringResource(FilmR.string.ce_continue_watching)
    private val uiTextNextUp = UiText.StringResource(FilmR.string.next_up)

    companion object {
        private val UUID_SUGGESTIONS = "31e47044-9b79-4bb0-99d0-0e477ed65420".toItemId()
        private val UUID_CONTINUE_WATCHING = "44845958-8326-4e83-beb4-c4f42e9eeb95".toItemId()
        private val UUID_NEXT_UP = "18bfced5-f237-4d42-aa72-d9d7fed19279".toItemId()

        /** CE (#810): see [clientActsFor]. */
        private const val CLIENT_SWITCH_WAIT_MS = 3_000L
        private const val CLIENT_SWITCH_POLL_MS = 20L
    }

    private val homeRowsStore = HomeRowsStore(appPreferences, storedServers)

    private val reloadPolicy = ReloadPolicy(dataChanges)
    private val serverAwayReload = ServerAwayReload()
    private var serverAway: Boolean? = null
    private var loadStartedVersion = -1L
    private var loadJob: Job? = null
    /**
     * The last library list, with the server and user it is for. CE (#810): one value, so a load a
     * switch overtook cannot pair one user's list with another user's key.
     */
    @Volatile private var cachedLibraries: Pair<Pair<ServerId?, UserId?>, List<Library>>? = null
    private var cacheChecked = false

    /**
     * CE (#810): counts the user switches. A load started before the last one is for the previous
     * user: what it still finds leaves the state alone ([updateState]).
     */
    private val generation = AtomicLong()

    /**
     * CE (#810): a switch moves [generation] on and invalidates the reload policy in one step, and
     * a load checks its generation and completes the policy in one step, so a load the switch
     * overtook cannot mark Home fresh after the switch.
     */
    private val switchLock = Any()

    /** CE (#810): the load a user switch started, while it runs. */
    private var switchLoad: Job? = null

    /** CE (#810): the server and user [followCurrentUser] saw last. Main thread only. */
    private var followedUser: Pair<ServerId?, UserId?>? = null

    init {
        syncPreferences()
        followCurrentUser()
    }

    /**
     * CE (#608): settings Home draws from without loading anything. Read when Home comes back into
     * view, so a change in Settings shows without a reload.
     */
    private fun syncPreferences() {
        val useEpisodeImages = appPreferences.getValue(appPreferences.homeEpisodeImages)
        _state.update { it.copy(useEpisodeImages = useEpisodeImages) }
    }

    /**
     * CE (#810): Home stays on the back stack while a user is added or picked, and [ReloadPolicy]
     * counts changes to the data, not who it is for: a Home loaded less than a minute before (after
     * the player, for one) kept the previous user's rows and avatar. So Home follows the current
     * user of the current server itself, as Room has it: every way of changing the user writes it
     * there.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun followCurrentUser() {
        viewModelScope.launch {
            appPreferences
                .observeCurrentServerId()
                .flatMapLatest { serverId ->
                    if (serverId == null) {
                        flowOf(null to null)
                    } else {
                        storedUsers.observeCurrentUser(serverId).map { user -> serverId to user }
                    }
                }
                .catch { e -> Timber.e(e, "Failed to follow the current user") }
                .collect { (serverId, user) -> onCurrentUser(serverId, user) }
        }
    }

    /**
     * When the current user changed, or the rows on screen are someone else's than [user]'s, they
     * go at once and [user]'s load starts, replacing one that was still loading for the previous
     * user. Until it arrives Home shows the new user's avatar and no rows (nothing at all when
     * nobody is signed in).
     */
    private fun onCurrentUser(serverId: ServerId?, user: User?) {
        val identity = serverId to user?.id
        val previous = followedUser
        followedUser = identity
        val shown = _state.value
        val shownIdentity = shown.user?.let { shown.server?.id to it.id }
        val switched =
            (previous != null && previous != identity) ||
                (shownIdentity != null && shownIdentity != identity)
        if (!switched) return
        Timber.i("The current user changed: Home starts over")
        synchronized(switchLock) {
            generation.incrementAndGet()
            reloadPolicy.invalidate()
        }
        loadJob?.cancel()
        cacheChecked = false
        _state.value =
            HomeState(
                server = shown.server?.takeIf { it.id == serverId },
                user = user,
                useEpisodeImages = shown.useEpisodeImages,
            )
        switchLoad = if (user != null) loadData() else null
    }

    /**
     * Tells Home whether the server is unreachable. When the server goes away while Home has rows
     * on screen, Home reloads so stale online rows clear to the unreachable state; when the server
     * comes back, Home reloads its rows.
     */
    fun onServerAwayChanged(away: Boolean) {
        val previous = serverAway
        serverAway = away
        val changed = serverAwayReload.onServerAway(away)
        val recoveredFromError = previous == true && !away && _state.value.error != null
        if (!changed && !recoveredFromError) return
        // Already empty with an error (e.g. cold-start failure before reachability flipped):
        // ErrorState updates from LocalServerStatus without firing another failing load.
        if (away && _state.value.isEmpty && _state.value.error != null) return
        loadData(silent = !_state.value.isEmpty)
    }

    /**
     * With [silent] the refresh indicator stays hidden, for reloads the user didn't ask for. The
     * returned job ends once every row has loaded, for screens that wait for that (TV Home puts
     * focus back only then, so it lands on the item in its new place). A load that starts while
     * another is running replaces it (#251), unless an identical load for the current reachability
     * and data version is already running.
     */
    fun loadData(silent: Boolean = false): Job {
        Timber.i("Loading data")
        if (
            loadJob?.isActive == true &&
                serverAwayReload.runningIsCurrent() &&
                loadStartedVersion == dataChanges.version.value
        ) {
            if (!silent) _state.update { it.copy(isLoading = true, error = null) }
            return loadJob!!
        }
        loadJob?.cancel()
        serverAwayReload.begin()
        loadStartedVersion = dataChanges.version.value
        val job =
            viewModelScope.launch(loadDispatcher + LoadGeneration(generation.get())) {
                runLoad(silent)
            }
        loadJob = job
        return job
    }

    /** Whether coming back to Home needs a load (#251), see [ReloadPolicy]. */
    fun shouldReload(stopEventFollows: Boolean = false): Boolean {
        syncPreferences()
        // CE (#810): the new user's rows are already on their way.
        if (switchLoad?.isActive == true) return false
        return reloadPolicy.shouldReload(stopEventFollows)
    }

    /** [loadData] when [shouldReload] says so, else a job that is already done. */
    fun loadDataIfNeeded(silent: Boolean = false): Job {
        switchLoad
            ?.takeIf { it.isActive }
            ?.let {
                return it
            }
        return if (shouldReload(stopEventFollows = false)) {
            loadData(silent)
        } else {
            Job().apply { complete() }
        }
    }

    /** CE (#810): the [generation] a load started in. */
    private class LoadGeneration(val value: Long) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<LoadGeneration>
    }

    /** Changes the state for the load this runs in, unless a user switch came after its start. */
    private suspend fun updateState(change: (HomeState) -> HomeState) =
        updateState(currentCoroutineContext()[LoadGeneration], change)

    private fun updateState(load: LoadGeneration?, change: (HomeState) -> HomeState) {
        _state.update { if (load != null && load.value != generation.get()) it else change(it) }
    }

    /**
     * CE (#810): waits, a few seconds at most, until the client acts for [userId]. False when it
     * does not by then (signed out in the meantime, or switched to someone else).
     */
    private suspend fun clientActsFor(userId: UserId): Boolean =
        withTimeoutOrNull(CLIENT_SWITCH_WAIT_MS) {
            while (runCatching { account.getUserId() }.getOrNull() != userId) {
                delay(CLIENT_SWITCH_POLL_MS)
            }
            true
        } ?: false

    private suspend fun runLoad(silent: Boolean) {
        val token = reloadPolicy.begin()
        if (!silent) updateState { it.copy(isLoading = true, error = null) }
        var server: Server? = null
        var loaded = false
        try {
            server = appPreferences.currentServerId()?.let { serverId -> loadServerName(serverId) }
            if (server != null) {
                val user = logFailure("user") { storedUsers.currentUser(server.id) }
                updateState { it.copy(user = user.getOrNull()) }
                // CE (#810): a login or switch writes Room before it switches the client, so a
                // load that a new current user started can be early: what it fetched would be the
                // previous user's rows under the new user's name.
                val userId = user.getOrNull()?.id
                if (userId != null && !clientActsFor(userId)) {
                    Timber.w("The client is not signed in as the current user: no Home load")
                    updateState { it.copy(isLoading = false) }
                    return
                }
                showCachedRows(server.id, user.getOrNull()?.id)
            }
            // CE (#142): the rows this user chose for this server, in their order.
            val stored = homeRowsStore.read(HomeRowsProfile.of(server))
            val legacy = homeRowsStore.legacy()
            val layout = HomeRows.resolve(stored, null, legacy)
            updateState { it.copy(rowOrder = layout.map { row -> row.key }) }
            fun show(type: HomeRowType) = layout.any { it.type == type && it.visible }

            // coroutineScope { } is required here: without it, a failure
            // inside any async { } child propagates straight to viewModelScope
            // and crashes the app, even with this try/catch around awaitAll.
            // See BUGREPORT_ANALYSIS.md. Each row catches its own failures, so one
            // row can't cancel the others; this stays for whatever gets past that.
            val rows = coroutineScope {
                // The user image only feeds the avatar: it must not hold the rows back.
                val avatar = server?.let { s -> async { refreshUserImage(s.id) } }
                // CE (#543): neither does the server's name.
                val name = server?.let { s -> async { refreshServerName(s) } }
                val results =
                    awaitAll(
                        async {
                            logFailure("suggestions") {
                                loadSuggestions(show(HomeRowType.SUGGESTIONS))
                            }
                        },
                        async {
                            logFailure("resume items") {
                                loadResumeItems(show(HomeRowType.CONTINUE_WATCHING))
                            }
                        },
                        async {
                            logFailure("next up items") {
                                loadNextUpItems(show(HomeRowType.NEXT_UP))
                            }
                        },
                        async { logFailure("views") { loadViews(stored, legacy) } },
                    )
                avatar?.await()
                name?.await()
                results
            }
            val error = errorIfAllFailed(rows)
            updateState {
                if (error != null) {
                    it.copy(
                        suggestionsSection = null,
                        resumeSection = null,
                        nextUpSection = null,
                        views = emptyList(),
                        error = error,
                    )
                } else {
                    it.copy(error = null)
                }
            }
            loaded = error == null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateState {
                it.copy(
                    suggestionsSection = null,
                    resumeSection = null,
                    nextUpSection = null,
                    views = emptyList(),
                    error = e,
                )
            }
        }
        updateState { it.copy(isLoading = false) }
        // CE (#810): a load overtaken by a user switch neither counts as fresh nor is cached. The
        // check and the completion are one step for a switch ([switchLock]).
        val load = currentCoroutineContext()[LoadGeneration]
        val current =
            synchronized(switchLock) {
                (loaded && load?.value == generation.get()).also {
                    if (it) reloadPolicy.complete(token)
                }
            }
        if (current) saveRows(server)
    }

    /** The avatar's image tag changes on the server; Room holds it, so read the user again. */
    private suspend fun refreshUserImage(serverId: ServerId) {
        logFailure("user image") {
            account.refreshCurrentUserImage()
            storedUsers.currentUser(serverId)?.let { user ->
                // CE (#810): a new picture of the same user. Another user is a switch, which
                // onCurrentUser handles, rows and all.
                updateState {
                    if (it.user == null || it.user.id == user.id) it.copy(user = user) else it
                }
            }
        }
    }

    /**
     * CE (#543): a server renamed on the server is renamed here too, on the next load. When the
     * server does not answer, the stored name stays.
     */
    private suspend fun refreshServerName(server: Server) {
        logFailure("server name") {
            val renamed = server.renamedTo(account.getServerInfo()) ?: return@logFailure
            // #988: only the name; the server on screen may be older than its row.
            storedServers.renameServer(renamed.id, renamed.name)
            updateState { if (it.server?.id == renamed.id) it.copy(server = renamed) else it }
        }
    }

    /**
     * Cold start (#251): the rows of the last full load, on screen before the network answers. Only
     * when nothing is on screen yet; the load that follows replaces them.
     */
    private suspend fun showCachedRows(serverId: ServerId, userId: UserId?) {
        val cache = homeCache ?: return
        if (userId == null || cacheChecked || serverAway == true || !_state.value.isEmpty) return
        cacheChecked = true
        if (!appPreferences.getValue(appPreferences.homeCache)) {
            // Turned off: whatever an earlier start wrote goes, so nothing stale is kept around.
            cache.clear()
            return
        }
        val rows = cache.read(serverId, userId) ?: return
        updateState {
            if (!it.isEmpty) {
                it
            } else {
                it.copy(
                    suggestionsSection = rows.suggestions,
                    resumeSection = rows.resume,
                    nextUpSection = rows.nextUp,
                    views = rows.views,
                    rowOrder = rows.rowOrder,
                    isLoading = false,
                )
            }
        }
    }

    /** The rows of a full load, for the next cold start (#251). */
    private suspend fun saveRows(server: Server?) {
        val cache = homeCache ?: return
        val user = _state.value.user ?: return
        if (server == null) return
        if (!appPreferences.getValue(appPreferences.homeCache)) return
        cache.write(server.id, user.id, _state.value)
    }

    /** Runs [block], turning a failure into a logged result. A row keeps what it showed. */
    private suspend fun <T> logFailure(name: String, block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to load $name")
            Result.failure(e)
        }

    /**
     * The first error, if no row loaded. Each row reports whether it asked the server at all, so
     * rows turned off in settings don't count as having loaded.
     */
    private fun errorIfAllFailed(rows: List<Result<Boolean>>): Exception? {
        val attempted = rows.filter { it.getOrDefault(true) }
        return if (attempted.all { it.isFailure }) {
            attempted.firstOrNull()?.exceptionOrNull() as Exception?
        } else {
            null
        }
    }

    private suspend fun loadServerName(serverId: ServerId): Server? {
        val server = storedServers.server(serverId)
        if (server != null) {
            updateState { it.copy(server = server) }
        }
        return server
    }

    private suspend fun loadSuggestions(show: Boolean): Boolean {
        Timber.i("Loading suggestions")
        if (!show) {
            updateState { it.copy(suggestionsSection = null) }
            return false
        }

        val items = lists.getSuggestions()

        val section =
            if (items.isEmpty()) {
                null
            } else {
                HomeItem.Suggestions(id = UUID_SUGGESTIONS, items = items)
            }

        updateState { it.copy(suggestionsSection = section) }
        return true
    }

    private suspend fun loadResumeItems(show: Boolean): Boolean {
        Timber.i("Loading resume items")
        if (!show) {
            updateState { it.copy(resumeSection = null) }
            return false
        }

        val resumeItems = lists.getResumeItems()

        val section =
            if (resumeItems.isEmpty()) {
                null
            } else {
                HomeItem.Section(
                    HomeSection(UUID_CONTINUE_WATCHING, uiTextContinueWatching, resumeItems)
                )
            }

        updateState { it.copy(resumeSection = section) }
        return true
    }

    private suspend fun loadNextUpItems(show: Boolean): Boolean {
        Timber.i("Loading next up items")
        if (!show) {
            updateState { it.copy(nextUpSection = null) }
            return false
        }

        val nextUpItems = catalog.getNextUp()

        val section =
            if (nextUpItems.isEmpty()) {
                null
            } else {
                HomeItem.Section(HomeSection(UUID_NEXT_UP, uiTextNextUp, nextUpItems))
            }

        updateState { it.copy(nextUpSection = section) }
        return true
    }

    private suspend fun loadViews(
        stored: List<HomeRowConfig>?,
        legacy: LegacyHomeSwitches,
    ): Boolean {
        Timber.i("Loading views")
        if (!HomeRows.needsLibraries(stored, legacy)) {
            updateState { it.copy(views = emptyList()) }
            return false
        }

        // The library list rarely changes: later loads start the Latest rows from the last list
        // right away and check the list alongside (#251).
        val key = _state.value.server?.id to _state.value.user?.id
        val known = cachedLibraries?.takeIf { it.first == key }?.second
        var anyLatestRequested = false
        coroutineScope {
            val fresh = async { logFailure("libraries") { lists.homeLibraries() } }
            val libraries = known ?: fresh.await().getOrThrow()
            cachedLibraries = key to libraries
            anyLatestRequested = loadLatest(libraries, stored, legacy)
            if (known != null) {
                val now = fresh.await().getOrNull()
                if (now != null && now.map { it.id } != known.map { it.id }) {
                    cachedLibraries = key to now
                    anyLatestRequested = loadLatest(now, stored, legacy) || anyLatestRequested
                } else if (!anyLatestRequested) {
                    fresh.await().getOrThrow()
                }
            }
        }
        return anyLatestRequested
    }

    /**
     * The Latest row of each library. Every row shows as soon as it arrives, in the order of the
     * libraries, so the slowest library holds back none of the others (#251). Returns whether at
     * least one library row was requested from the server.
     */
    private suspend fun loadLatest(
        libraries: List<Library>,
        stored: List<HomeRowConfig>?,
        legacy: LegacyHomeSwitches,
    ): Boolean {
        // CE (#142): now that the libraries are known, new ones get their place and hidden ones go.
        val layout = HomeRows.resolve(stored, libraries.map { it.id }, legacy)
        updateState { it.copy(rowOrder = layout.map { row -> row.key }) }
        val shown = layout.filter { it.visible }.mapNotNullTo(mutableSetOf()) { it.libraryId }
        val views = libraries.filter { it.id in shown }
        if (views.isEmpty()) {
            // CE (#428): no Latest row to load, so none of the old ones may stay.
            updateState { it.copy(views = emptyList()) }
            return false
        }

        val current = _state.value.views
        val results = arrayOfNulls<Result<List<Item>>>(views.size)
        // Rows that haven't arrived keep what they showed; the order is that of [views].
        fun items() =
            views.indices.mapNotNull { i ->
                val view = views[i]
                val result = results[i]
                when {
                    result == null -> current.firstOrNull { it.id == view.id }
                    result.isSuccess ->
                        result.getOrThrow().let { latest ->
                            if (latest.isEmpty()) null else HomeItem.ViewItem(view.toView(latest))
                        }
                    else -> current.firstOrNull { it.id == view.id }
                }
            }

        val load = currentCoroutineContext()[LoadGeneration]
        coroutineScope {
            views
                .mapIndexed { i, view ->
                    async {
                        val result =
                            logFailure("library ${view.id.asString()}") {
                                lists.getLatestMedia(view.id, view.type.latestItemTypes)
                            }
                        synchronized(results) {
                            results[i] = result
                            updateState(load) { it.copy(views = items()) }
                        }
                    }
                }
                .awaitAll()
        }
        // With nothing loaded, this is the row failing as a whole.
        if (results.isNotEmpty() && results.all { it?.isFailure == true }) {
            throw results.first()!!.exceptionOrNull()!!
        }
        return true
    }

    /** The backdrop of a season, for the hero of an episode. Null when no server is set. */
    fun seasonBackdropUrl(seasonId: ItemId): String? = catalog.seasonBackdropUrl(seasonId)

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.OnRetryClick -> {
                loadData()
            }
            else -> Unit
        }
    }
}
