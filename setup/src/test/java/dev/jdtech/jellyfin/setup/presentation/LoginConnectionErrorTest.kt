package dev.jdtech.jellyfin.setup.presentation

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.domain.FakeLocalNetworkAccess
import dev.jdtech.jellyfin.setup.domain.ServerLocalNetworkBlock
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.domain.parseIpAddress
import dev.jdtech.jellyfin.setup.presentation.login.LoginAction
import dev.jdtech.jellyfin.setup.presentation.login.LoginViewModel
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * CE (#571): a login that could not reach the server said "Unknown error", and Quick Connect showed
 * the exception's own text. Both now say that the server can't be reached, and name the local
 * network permission when that is what blocks a server on the local network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginConnectionErrorTest {
    private var failure: Exception = IllegalStateException("boom")

    private val unused =
        Proxy.newProxyInstance(
            SetupRepository::class.java.classLoader,
            arrayOf(SetupRepository::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException(method.name)
        } as SetupRepository

    private val repository =
        object : SetupRepository by unused {
            override suspend fun login(username: String, password: String) = throw failure

            override suspend fun initiateQuickConnect(): QuickConnectResult = throw failure
        }

    /** What the SDK throws when the connection times out. */
    private val unreachable =
        TimeoutException("Connection timed out", SocketTimeoutException("failed to connect"))

    private fun viewModel(denied: Boolean, address: String = "http://10.0.2.2:8096") =
        LoginViewModel(
            repository,
            ServerLocalNetworkBlock(
                access = FakeLocalNetworkAccess(denied = denied),
                serverAddress = { address },
                resolve = { host -> parseIpAddress(host)?.let(::listOf) ?: emptyList() },
            ),
        )

    private fun LoginViewModel.errorResource(): Int? =
        (state.value.error as? UiText.StringResource)?.resId

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a login that cannot reach the server says so`() = runTest {
        failure = unreachable
        val viewModel = viewModel(denied = false)

        viewModel.onAction(LoginAction.OnLoginClick("demo", ""))

        assertEquals(CoreR.string.download_error_server_unreachable, viewModel.errorResource())
    }

    @Test
    fun `a login blocked by the local network permission names it`() = runTest {
        failure = unreachable
        val viewModel = viewModel(denied = true)

        viewModel.onAction(LoginAction.OnLoginClick("demo", ""))

        assertEquals(SetupR.string.ce_add_server_error_local_network, viewModel.errorResource())
    }

    @Test
    fun `access denied does not explain a server on the internet`() = runTest {
        failure = unreachable
        val viewModel = viewModel(denied = true, address = "https://8.8.8.8:8096")

        viewModel.onAction(LoginAction.OnLoginClick("demo", ""))

        assertEquals(CoreR.string.download_error_server_unreachable, viewModel.errorResource())
    }

    @Test
    fun `a wrong password and other failures keep their text`() = runTest {
        val viewModel = viewModel(denied = true)

        failure = InvalidStatusException(401, null)
        viewModel.onAction(LoginAction.OnLoginClick("demo", "wrong"))
        assertEquals(SetupR.string.login_error_wrong_username_password, viewModel.errorResource())

        failure = InvalidStatusException(500, null)
        viewModel.onAction(LoginAction.OnLoginClick("demo", ""))
        assertEquals(CoreR.string.unknown_error, viewModel.errorResource())
    }

    @Test
    fun `quick connect that cannot reach the server says so instead of the exception text`() =
        runTest {
            failure = unreachable

            val open = viewModel(denied = false)
            open.onAction(LoginAction.OnQuickConnectClick)
            assertEquals(CoreR.string.download_error_server_unreachable, open.errorResource())

            val blocked = viewModel(denied = true)
            blocked.onAction(LoginAction.OnQuickConnectClick)
            assertEquals(SetupR.string.ce_add_server_error_local_network, blocked.errorResource())
        }
}
