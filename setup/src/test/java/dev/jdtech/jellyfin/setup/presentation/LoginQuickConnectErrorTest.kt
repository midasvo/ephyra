package dev.jdtech.jellyfin.setup.presentation

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.domain.FakeLocalNetworkAccess
import dev.jdtech.jellyfin.setup.domain.ServerLocalNetworkBlock
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.presentation.login.LoginAction
import dev.jdtech.jellyfin.setup.presentation.login.LoginViewModel
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CE (#606): a Quick Connect that fails for a reason other than the connection showed the
 * exception's own text, such as "Invalid HTTP status in response: 404" for a code that expired.
 * Each failure now has plain words, and a server with Quick Connect turned off loses the button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginQuickConnectErrorTest {
    private val scheduler = TestCoroutineScheduler()

    /** Thrown by the call of that name, or `null` for it to succeed. */
    private val failures = mutableMapOf<String, Exception>()
    private var approved = false

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun result() =
        QuickConnectResult(
            authenticated = approved,
            secret = "secret",
            code = "123456",
            deviceId = "device",
            deviceName = "device",
            appName = "Ephyra",
            appVersion = "test",
            dateAdded = LocalDateTime.now(),
        )

    private val repository =
        Proxy.newProxyInstance(
            SetupRepository::class.java.classLoader,
            arrayOf(SetupRepository::class.java),
        ) { _, method, _ ->
            failures[method.name]?.let { throw it }
            when (method.name) {
                "initiateQuickConnect",
                "getQuickConnectState" -> result()
                "loginWithSecret" -> Unit
                "getIsQuickConnectEnabled" -> true
                "toString" -> "FakeSetupRepository"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SetupRepository

    private fun viewModel() =
        LoginViewModel(repository, ServerLocalNetworkBlock(FakeLocalNetworkAccess(), { null }))

    private fun LoginViewModel.error(): UiText? = state.value.error

    private fun LoginViewModel.errorResource(): Int? =
        (state.value.error as? UiText.StringResource)?.resId

    @Test
    fun `a code that expired says so`() =
        runTest(scheduler) {
            val viewModel = viewModel()
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()
            assertEquals("123456", viewModel.state.value.quickConnectCode)

            // Ten minutes later the server no longer knows the code.
            failures["getQuickConnectState"] = InvalidStatusException(404, null)
            advanceTimeBy(5_001L)
            runCurrent()

            assertEquals(SetupR.string.ce_quick_connect_error_expired, viewModel.errorResource())
            assertNull(viewModel.state.value.quickConnectCode)
        }

    @Test
    fun `a code refused at login says that it expired`() =
        runTest(scheduler) {
            approved = true
            failures["loginWithSecret"] = InvalidStatusException(401, null)
            val viewModel = viewModel()
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()

            assertEquals(SetupR.string.ce_quick_connect_error_expired, viewModel.errorResource())
        }

    @Test
    fun `quick connect turned off on the server says so and hides the button`() =
        runTest(scheduler) {
            val viewModel = viewModel()
            viewModel.loadQuickConnectEnabled()
            runCurrent()
            assertTrue(viewModel.state.value.quickConnectEnabled)

            failures["initiateQuickConnect"] = InvalidStatusException(401, null)
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()

            assertEquals(SetupR.string.ce_quick_connect_error_disabled, viewModel.errorResource())
            assertFalse(viewModel.state.value.quickConnectEnabled)
        }

    @Test
    fun `quick connect turned off while waiting for approval says so`() =
        runTest(scheduler) {
            val viewModel = viewModel()
            viewModel.loadQuickConnectEnabled()
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()

            failures["getQuickConnectState"] = InvalidStatusException(403, null)
            advanceTimeBy(5_001L)
            runCurrent()

            assertEquals(SetupR.string.ce_quick_connect_error_disabled, viewModel.errorResource())
            assertFalse(viewModel.state.value.quickConnectEnabled)
        }

    @Test
    fun `another server error gives its status`() =
        runTest(scheduler) {
            failures["initiateQuickConnect"] = InvalidStatusException(500, null)
            val viewModel = viewModel()
            viewModel.loadQuickConnectEnabled()
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()

            val error = viewModel.error() as UiText.StringResource
            assertEquals(CoreR.string.ce_error_server_status, error.resId)
            assertEquals(listOf<Any>(500), error.args.toList())
            // Not a sign that Quick Connect is off.
            assertTrue(viewModel.state.value.quickConnectEnabled)
        }

    @Test
    fun `an answer that is not Jellyfin's, or an error without a message, is unknown`() =
        runTest(scheduler) {
            val viewModel = viewModel()

            // What the client throws for an HTML page from a proxy: its text is not for users.
            failures["initiateQuickConnect"] =
                IllegalStateException("Unexpected JSON token at offset 0: Expected start of object")
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()
            assertEquals(CoreR.string.unknown_error, viewModel.errorResource())

            failures["initiateQuickConnect"] = NullPointerException()
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()
            assertEquals(CoreR.string.unknown_error, viewModel.errorResource())
        }

    @Test
    fun `a new code clears the error of the attempt before`() =
        runTest(scheduler) {
            failures["initiateQuickConnect"] = InvalidStatusException(500, null)
            val viewModel = viewModel()
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()
            assertTrue(viewModel.error() != null)

            failures.clear()
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()
            assertEquals("123456", viewModel.state.value.quickConnectCode)
            assertNull(viewModel.error())

            // Stop the polling so runTest can finish.
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()
        }
}
