package dev.jdtech.jellyfin.setup.presentation

import dev.jdtech.jellyfin.setup.domain.FakeLocalNetworkAccess
import dev.jdtech.jellyfin.setup.domain.ServerLocalNetworkBlock
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.presentation.login.LoginAction
import dev.jdtech.jellyfin.setup.presentation.login.LoginViewModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * CE (#516): the TV login screen passes the username as typed, so a space around it failed the
 * login. The ViewModel trims the username, for the phone and TV alike, and leaves the password as
 * typed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginUsernameTrimTest {
    private val logins = mutableListOf<Pair<String, String>>()

    private val repository =
        Proxy.newProxyInstance(
            SetupRepository::class.java.classLoader,
            arrayOf(SetupRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "login" -> {
                    logins.add(args[0] as String to args[1] as String)
                    Unit
                }
                "toString" -> "FakeSetupRepository"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SetupRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a username with spaces around it reaches the repository trimmed`() = runTest {
        val viewModel = LoginViewModel(repository, ServerLocalNetworkBlock(FakeLocalNetworkAccess(), { null }))

        viewModel.onAction(LoginAction.OnLoginClick(" demo  ", " pass word "))

        assertEquals(listOf("demo" to " pass word "), logins)
    }
}
