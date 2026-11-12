package dev.jdtech.jellyfin.setup.presentation

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
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * CE (#382): pressing Quick Connect again cancels it. The code must go with the polling, so the
 * button never shows a code that the app no longer waits for, and the next press starts a new one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginQuickConnectTest {
    private val scheduler = TestCoroutineScheduler()
    private var initiated = 0
    private var polls = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a second press cancels the code and a third press starts a new one`() =
        runTest(scheduler) {
            val viewModel = LoginViewModel(fakeRepository(), ServerLocalNetworkBlock(FakeLocalNetworkAccess(), { null }))

            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()
            assertEquals("000001", viewModel.state.value.quickConnectCode)

            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()
            assertNull(viewModel.state.value.quickConnectCode)

            // Nothing polls the cancelled code any more.
            val pollsAtCancel = polls
            advanceTimeBy(20_000L)
            runCurrent()
            assertEquals(pollsAtCancel, polls)
            assertNull(viewModel.state.value.quickConnectCode)

            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()
            assertEquals("000002", viewModel.state.value.quickConnectCode)
            assertEquals(2, initiated)

            // Stop the polling so runTest can finish.
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            runCurrent()
        }

    @Test
    fun `a fast double press leaves no code and nothing polling`() =
        runTest(scheduler) {
            val viewModel = LoginViewModel(fakeRepository(), ServerLocalNetworkBlock(FakeLocalNetworkAccess(), { null }))

            // Both presses land before the first one has asked the server for a code.
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            viewModel.onAction(LoginAction.OnQuickConnectClick)
            advanceTimeBy(20_000L)
            runCurrent()

            assertNull(viewModel.state.value.quickConnectCode)
            assertEquals(0, polls)
        }

    private fun result(code: String) =
        QuickConnectResult(
            authenticated = false,
            secret = "secret-$code",
            code = code,
            deviceId = "device",
            deviceName = "device",
            appName = "Ephyra",
            appVersion = "test",
            dateAdded = LocalDateTime.now(),
        )

    private fun fakeRepository(): SetupRepository =
        Proxy.newProxyInstance(
            SetupRepository::class.java.classLoader,
            arrayOf(SetupRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "initiateQuickConnect" -> {
                    initiated++
                    result(initiated.toString().padStart(6, '0'))
                }
                "getQuickConnectState" -> {
                    polls++
                    result(initiated.toString().padStart(6, '0'))
                }
                "toString" -> "FakeSetupRepository"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SetupRepository
}
