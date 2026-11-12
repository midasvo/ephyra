package dev.jdtech.jellyfin.setup.presentation

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.settings.domain.AppPreferences
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
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#606): an Add server failure that was not one of the repository's own texts showed the
 * exception's text. It now says it in plain words (#377).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AddServerUnexpectedErrorTest {
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
            override suspend fun addServer(address: String) = throw failure
        }

    private val appPreferences =
        AppPreferences(
            RuntimeEnvironment.getApplication()
                .getSharedPreferences("add-server-unexpected-error-test", Context.MODE_PRIVATE)
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun AddServerViewModel.error(): UiText.StringResource =
        state.value.error!!.single() as UiText.StringResource

    @Test
    fun `an unexpected failure says unknown error, not its own text`() = runTest {
        failure = IllegalArgumentException("Unexpected JSON token at offset 0")
        val viewModel = AddServerViewModel(repository, appPreferences, FakeLocalNetworkAccess())

        viewModel.onAction(AddServerAction.OnConnectClick("10.0.2.2:8096"))

        assertEquals(CoreR.string.unknown_error, viewModel.error().resId)
    }

    @Test
    fun `a server error gives its status`() = runTest {
        failure = InvalidStatusException(502, null)
        val viewModel = AddServerViewModel(repository, appPreferences, FakeLocalNetworkAccess())

        viewModel.onAction(AddServerAction.OnConnectClick("10.0.2.2:8096"))

        assertEquals(CoreR.string.ce_error_server_status, viewModel.error().resId)
        assertEquals(listOf<Any>(502), viewModel.error().args.toList())
    }
}
