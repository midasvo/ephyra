package dev.jdtech.jellyfin.setup.presentation

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ProfileLockType
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.profilelock.ProfileLock
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.presentation.pin.PinError
import dev.jdtech.jellyfin.setup.presentation.pin.PinStep
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinGateAction
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinGateViewModel
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinSettingsAction
import dev.jdtech.jellyfin.setup.presentation.pin.ProfilePinSettingsViewModel
import dev.jdtech.jellyfin.setup.presentation.users.UsersAction
import dev.jdtech.jellyfin.setup.presentation.users.UsersEvent
import dev.jdtech.jellyfin.setup.presentation.users.UsersViewModel
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CE (#299): the profile switch, the start lock and the Profile PIN settings, on a fake server with
 * two saved users. The demo server used for device tests has one user, so the switch between two
 * profiles is only covered here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePinSwitchTest {
    private val adult = User(id = UUID.randomUUID(), name = "Adult", serverId = "s")
    private val kid = User(id = UUID.randomUUID(), name = "Kid", serverId = "s")
    private val users = mutableMapOf(adult.id to adult, kid.id to kid)
    private val server =
        Server(id = "s", name = "Home", currentServerAddressId = null, currentUserId = kid.id)
    private val lock = ProfileLock(fakeDao(), Dispatchers.Unconfined)
    private val repository = fakeRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking {
            lock.setPin(adult.id, "2468")
            // The app is in the kid's profile.
            lock.unlock(kid.id)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `switching to a profile with a PIN asks for it and does not switch yet`() = runTest {
        val viewModel = UsersViewModel(repository, lock)
        viewModel.loadUsers()

        viewModel.onAction(UsersAction.OnUserClick(adult.id))

        assertEquals(adult.id, viewModel.state.value.pinPrompt?.userId)
        assertEquals("Adult", viewModel.state.value.pinPrompt?.userName)
        assertEquals(kid.id, server.currentUserId)
    }

    @Test
    fun `a wrong PIN keeps the old profile`() = runTest {
        val viewModel = UsersViewModel(repository, lock)
        viewModel.loadUsers()
        viewModel.onAction(UsersAction.OnUserClick(adult.id))

        viewModel.onAction(UsersAction.OnPinSubmit("1111"))
        viewModel.onAction(UsersAction.OnPinSubmit("2222"))

        val prompt = viewModel.state.value.pinPrompt
        assertNotNull(prompt)
        assertEquals(PinError.WRONG, prompt!!.error)
        assertEquals(2, prompt.errorCount)
        assertEquals(kid.id, server.currentUserId)
    }

    @Test
    fun `the right PIN switches and opens Home`() = runTest {
        val viewModel = UsersViewModel(repository, lock)
        viewModel.loadUsers()
        viewModel.onAction(UsersAction.OnUserClick(adult.id))

        viewModel.onAction(UsersAction.OnPinSubmit("2468"))

        assertEquals(UsersEvent.NavigateToHome, viewModel.events.first())
        assertNull(viewModel.state.value.pinPrompt)
        assertEquals(adult.id, server.currentUserId)
        assertFalse(ProfileLock.isLockedOnStart(users[adult.id], lock.unlockedUserIds.value))
    }

    @Test
    fun `cancelling the prompt stays on the old profile`() = runTest {
        val viewModel = UsersViewModel(repository, lock)
        viewModel.loadUsers()
        viewModel.onAction(UsersAction.OnUserClick(adult.id))

        viewModel.onAction(UsersAction.OnPinDismiss)

        assertNull(viewModel.state.value.pinPrompt)
        assertEquals(kid.id, server.currentUserId)
    }

    @Test
    fun `a profile without a PIN switches straight away, and switching back asks again`() =
        runTest {
            lock.unlock(adult.id)
            server.currentUserId = adult.id
            val viewModel = UsersViewModel(repository, lock)
            viewModel.loadUsers()

            viewModel.onAction(UsersAction.OnUserClick(kid.id))
            assertEquals(UsersEvent.NavigateToHome, viewModel.events.first())
            assertEquals(kid.id, server.currentUserId)

            viewModel.onAction(UsersAction.OnUserClick(adult.id))
            assertEquals(adult.id, viewModel.state.value.pinPrompt?.userId)
            assertEquals(kid.id, server.currentUserId)
        }

    @Test
    fun `the start lock opens with the right PIN and cancelling leaves the profile`() = runTest {
        server.currentUserId = adult.id
        val restarted = ProfileLock(fakeDao(), Dispatchers.Unconfined)
        assertTrue(ProfileLock.isLockedOnStart(users[adult.id], restarted.unlockedUserIds.value))

        val gate = ProfilePinGateViewModel(repository, restarted)
        gate.onAction(ProfilePinGateAction.OnPinSubmit("0000"))
        assertEquals(PinError.WRONG, gate.state.value.prompt?.error)
        assertTrue(ProfileLock.isLockedOnStart(users[adult.id], restarted.unlockedUserIds.value))

        gate.onAction(ProfilePinGateAction.OnPinSubmit("2468"))
        assertFalse(ProfileLock.isLockedOnStart(users[adult.id], restarted.unlockedUserIds.value))

        ProfilePinGateViewModel(repository, ProfileLock(fakeDao(), Dispatchers.Unconfined))
            .onAction(ProfilePinGateAction.OnCancel)
        assertNull(server.currentUserId)
    }

    @Test
    fun `setting a PIN asks twice and a mismatch starts over`() = runTest {
        val viewModel = ProfilePinSettingsViewModel(repository, lock)
        assertFalse(viewModel.state.value.hasPin)

        viewModel.onAction(ProfilePinSettingsAction.OnSetClick)
        assertEquals(PinStep.New, viewModel.state.value.step)
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("1357"))
        assertEquals(PinStep.Confirm("1357"), viewModel.state.value.step)
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("1358"))
        assertEquals(PinStep.New, viewModel.state.value.step)
        assertEquals(PinError.MISMATCH, viewModel.state.value.prompt?.error)
        assertFalse(lock.hasPin(kid.id))

        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("1357"))
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("1357"))

        assertNull(viewModel.state.value.step)
        assertTrue(viewModel.state.value.hasPin)
        assertTrue(lock.verifyPin(kid.id, "1357"))
        // Setting it from inside the profile does not lock the profile in use.
        assertFalse(ProfileLock.isLockedOnStart(users[kid.id], lock.unlockedUserIds.value))
    }

    @Test
    fun `changing and removing a PIN ask for the current one`() = runTest {
        server.currentUserId = adult.id
        lock.unlock(adult.id)
        val viewModel = ProfilePinSettingsViewModel(repository, lock)

        viewModel.onAction(ProfilePinSettingsAction.OnChangeClick)
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("0000"))
        assertEquals(PinStep.Current(forRemoval = false), viewModel.state.value.step)
        assertEquals(PinError.WRONG, viewModel.state.value.prompt?.error)
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("2468"))
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("8642"))
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("8642"))
        assertTrue(lock.verifyPin(adult.id, "8642"))
        assertFalse(lock.verifyPin(adult.id, "2468"))

        viewModel.onAction(ProfilePinSettingsAction.OnRemoveClick)
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("2468"))
        assertTrue(lock.hasPin(adult.id))
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("8642"))
        assertFalse(lock.hasPin(adult.id))
        assertFalse(viewModel.state.value.hasPin)
    }

    // CE (#330): the pattern lock.
    private val square = listOf(0, 1, 4, 3)
    private val zed = listOf(0, 1, 2, 4, 6, 7, 8)

    @Test
    fun `setting a pattern draws it twice and a mismatch starts over`() = runTest {
        val viewModel = ProfilePinSettingsViewModel(repository, lock)

        viewModel.onAction(ProfilePinSettingsAction.OnSetLockClick(ProfileLockType.PATTERN))
        assertEquals(PinStep.New, viewModel.state.value.step)
        assertEquals(ProfileLockType.PATTERN, viewModel.state.value.prompt?.lockType)
        // Too short: not taken, the step stays.
        viewModel.onAction(ProfilePinSettingsAction.OnPatternSubmit(listOf(0, 1, 2)))
        assertEquals(PinStep.New, viewModel.state.value.step)
        viewModel.onAction(ProfilePinSettingsAction.OnPatternSubmit(zed))
        assertEquals(PinStep.Confirm("0-1-2-4-6-7-8"), viewModel.state.value.step)
        viewModel.onAction(ProfilePinSettingsAction.OnPatternSubmit(square))
        assertEquals(PinStep.New, viewModel.state.value.step)
        assertEquals(PinError.MISMATCH, viewModel.state.value.prompt?.error)
        assertEquals(ProfileLockType.PATTERN, viewModel.state.value.prompt?.lockType)
        assertFalse(lock.hasPin(kid.id))

        viewModel.onAction(ProfilePinSettingsAction.OnPatternSubmit(zed))
        viewModel.onAction(ProfilePinSettingsAction.OnPatternSubmit(zed))

        assertNull(viewModel.state.value.step)
        assertEquals(ProfileLockType.PATTERN, viewModel.state.value.lockType)
        assertEquals(ProfileLockType.PATTERN, viewModel.state.value.changedLockType)
        assertTrue(lock.verifyPattern(kid.id, zed))
        assertFalse(ProfileLock.isLockedOnStart(users[kid.id], lock.unlockedUserIds.value))
    }

    @Test
    fun `switching between PIN and pattern asks for the current lock`() = runTest {
        server.currentUserId = adult.id
        lock.unlock(adult.id)
        val viewModel = ProfilePinSettingsViewModel(repository, lock)

        // PIN to pattern: the PIN first. A pattern is not the current lock.
        viewModel.onAction(ProfilePinSettingsAction.OnSetLockClick(ProfileLockType.PATTERN))
        assertEquals(PinStep.Current(forRemoval = false), viewModel.state.value.step)
        assertEquals(ProfileLockType.PIN, viewModel.state.value.prompt?.lockType)
        viewModel.onAction(ProfilePinSettingsAction.OnPatternSubmit(square))
        assertEquals(PinError.WRONG, viewModel.state.value.prompt?.error)
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("2468"))
        assertEquals(PinStep.New, viewModel.state.value.step)
        assertEquals(ProfileLockType.PATTERN, viewModel.state.value.prompt?.lockType)
        viewModel.onAction(ProfilePinSettingsAction.OnPatternSubmit(square))
        viewModel.onAction(ProfilePinSettingsAction.OnPatternSubmit(square))
        assertEquals(ProfileLockType.PATTERN, lock.lockType(adult.id))
        assertFalse(lock.verifyPin(adult.id, "2468"))

        // And back: the pattern first.
        viewModel.onAction(ProfilePinSettingsAction.OnSetLockClick(ProfileLockType.PIN))
        assertEquals(ProfileLockType.PATTERN, viewModel.state.value.prompt?.lockType)
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("2468"))
        assertEquals(PinError.WRONG, viewModel.state.value.prompt?.error)
        viewModel.onAction(ProfilePinSettingsAction.OnPatternSubmit(square))
        assertEquals(ProfileLockType.PIN, viewModel.state.value.prompt?.lockType)
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("1111"))
        viewModel.onAction(ProfilePinSettingsAction.OnPinSubmit("1111"))
        assertEquals(ProfileLockType.PIN, lock.lockType(adult.id))
        assertTrue(lock.verifyPin(adult.id, "1111"))
        assertFalse(lock.verifyPattern(adult.id, square))
    }

    @Test
    fun `removing a pattern asks for it`() = runTest {
        server.currentUserId = adult.id
        lock.setPattern(adult.id, square)
        val viewModel = ProfilePinSettingsViewModel(repository, lock)

        viewModel.onAction(ProfilePinSettingsAction.OnRemoveClick)
        assertEquals(ProfileLockType.PATTERN, viewModel.state.value.prompt?.lockType)
        viewModel.onAction(ProfilePinSettingsAction.OnPatternSubmit(zed))
        assertTrue(lock.hasPin(adult.id))
        viewModel.onAction(ProfilePinSettingsAction.OnPatternSubmit(square))
        assertNull(lock.lockType(adult.id))
        assertEquals(ProfileLockType.PATTERN, viewModel.state.value.changedLockType)
    }

    @Test
    fun `switching to a pattern profile asks for the pattern`() = runTest {
        lock.setPattern(adult.id, square)
        lock.unlock(kid.id)
        val viewModel = UsersViewModel(repository, lock)
        viewModel.loadUsers()

        viewModel.onAction(UsersAction.OnUserClick(adult.id))
        assertEquals(ProfileLockType.PATTERN, viewModel.state.value.pinPrompt?.lockType)
        viewModel.onAction(UsersAction.OnPatternSubmit(zed))
        assertEquals(PinError.WRONG, viewModel.state.value.pinPrompt?.error)
        assertEquals(kid.id, server.currentUserId)

        viewModel.onAction(UsersAction.OnPatternSubmit(square))
        assertEquals(UsersEvent.NavigateToHome, viewModel.events.first())
        assertEquals(adult.id, server.currentUserId)
    }

    @Test
    fun `the start lock of a pattern profile shows the grid`() = runTest {
        lock.setPattern(adult.id, square)
        server.currentUserId = adult.id
        val restarted = ProfileLock(fakeDao(), Dispatchers.Unconfined)

        val gate = ProfilePinGateViewModel(repository, restarted)
        assertEquals(ProfileLockType.PATTERN, gate.state.value.prompt?.lockType)
        gate.onAction(ProfilePinGateAction.OnPatternSubmit(zed))
        assertEquals(PinError.WRONG, gate.state.value.prompt?.error)
        assertTrue(ProfileLock.isLockedOnStart(users[adult.id], restarted.unlockedUserIds.value))

        gate.onAction(ProfilePinGateAction.OnPatternSubmit(square))
        assertFalse(ProfileLock.isLockedOnStart(users[adult.id], restarted.unlockedUserIds.value))
    }

    private fun fakeDao(): ServerDatabaseDao =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getUser" -> users[args[0] as UUID]
                "updateUserPin" -> {
                    val id = args[0] as UUID
                    users[id]?.let {
                        users[id] =
                            it.copy(
                                pinHash = args[1] as String?,
                                pinSalt = args[2] as String?,
                                lockType = "pin",
                            )
                    }
                    Unit
                }
                "updateUserPattern" -> {
                    val id = args[0] as UUID
                    users[id]?.let {
                        users[id] =
                            it.copy(
                                pinHash = args[1] as String,
                                pinSalt = args[2] as String,
                                lockType = "pattern",
                            )
                    }
                    Unit
                }
                "updateUserPinOnStart" -> {
                    val id = args[0] as UUID
                    users[id]?.let { users[id] = it.copy(pinOnStart = args[1] as Boolean) }
                    Unit
                }
                "toString" -> "FakeServerDatabaseDao"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ServerDatabaseDao

    private fun fakeRepository(): SetupRepository =
        Proxy.newProxyInstance(
            SetupRepository::class.java.classLoader,
            arrayOf(SetupRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getCurrentServer" -> server
                "getUsers" -> users.values.toList()
                "getPublicUsers" -> emptyList<User>()
                "getCurrentUser" -> server.currentUserId?.let { users[it] }
                "setCurrentUser" -> {
                    server.currentUserId = args[0] as UUID
                    Unit
                }
                "clearCurrentUser" -> {
                    server.currentUserId = null
                    Unit
                }
                "toString" -> "FakeSetupRepository"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SetupRepository
}
