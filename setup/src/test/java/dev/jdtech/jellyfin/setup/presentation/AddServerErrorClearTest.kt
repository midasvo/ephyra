package dev.jdtech.jellyfin.setup.presentation

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.models.ExceptionUiText
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.domain.FakeLocalNetworkAccess
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerAction
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerViewModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#443): "Server not found" stayed under the address field while another address was typed. The
 * error belongs to the address that was tried, so a change in the field clears it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AddServerErrorClearTest {
    private val notFound = UiText.StringResource(SetupR.string.add_server_error_not_found)

    private val unused =
        Proxy.newProxyInstance(
            SetupRepository::class.java.classLoader,
            arrayOf(SetupRepository::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException(method.name)
        } as SetupRepository

    private val repository =
        object : SetupRepository by unused {
            override suspend fun addServer(address: String) = throw ExceptionUiText(notFound)
        }

    private val appPreferences =
        AppPreferences(
            RuntimeEnvironment.getApplication()
                .getSharedPreferences("add-server-error-clear-test", Context.MODE_PRIVATE)
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `typing in the address field clears the error of the address that was tried`() = runTest {
        val viewModel = AddServerViewModel(repository, appPreferences, FakeLocalNetworkAccess())

        viewModel.onAction(AddServerAction.OnConnectClick("10.0.2.2:9"))
        assertSame(notFound, viewModel.state.value.error?.single())

        viewModel.onAction(AddServerAction.OnAddressChange)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `a change in the field without an error leaves the state alone`() = runTest {
        val viewModel = AddServerViewModel(repository, appPreferences, FakeLocalNetworkAccess())
        val before = viewModel.state.value

        viewModel.onAction(AddServerAction.OnAddressChange)

        assertEquals(before, viewModel.state.value)
    }
}
