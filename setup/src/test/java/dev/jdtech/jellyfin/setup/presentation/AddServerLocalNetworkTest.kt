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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#376): with local network access denied on Android 17, Add server searched without a word
 * and answered a LAN address with "Server not found". The state now says that access is denied, the
 * search waits for it, and the error for a local address names the permission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AddServerLocalNetworkTest {
    private var discoveries = 0

    private val unused =
        Proxy.newProxyInstance(
            SetupRepository::class.java.classLoader,
            arrayOf(SetupRepository::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException(method.name)
        } as SetupRepository

    private val repository =
        object : SetupRepository by unused {
            override fun discoverServers(): Flow<ServerDiscoveryInfo> {
                discoveries++
                return emptyFlow()
            }

            override suspend fun addServer(address: String) =
                throw ExceptionUiText(
                    UiText.StringResource(SetupR.string.add_server_error_not_found)
                )
        }

    private val appPreferences =
        AppPreferences(
            RuntimeEnvironment.getApplication()
                .getSharedPreferences("add-server-local-network-test", Context.MODE_PRIVATE)
        )

    private fun errorOf(viewModel: AddServerViewModel): Int =
        (viewModel.state.value.error!!.single() as UiText.StringResource).resId

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `with access denied the screen says so and does not search`() = runTest {
        val access = FakeLocalNetworkAccess(denied = true, settingName = "Nearby devices")
        val viewModel = AddServerViewModel(repository, appPreferences, access)

        viewModel.discoverServers()
        viewModel.onAction(AddServerAction.OnResume)

        assertTrue(viewModel.state.value.localNetworkDenied)
        assertEquals("Nearby devices", viewModel.state.value.localNetworkSettingName)
        assertEquals(0, discoveries)
    }

    @Test
    fun `the search runs once access has returned`() = runTest {
        val access = FakeLocalNetworkAccess(denied = true)
        val viewModel = AddServerViewModel(repository, appPreferences, access)
        viewModel.discoverServers()

        access.denied = false
        viewModel.onAction(AddServerAction.OnResume)

        assertFalse(viewModel.state.value.localNetworkDenied)
        assertEquals(1, discoveries)

        // Coming to the front again does not search again.
        viewModel.onAction(AddServerAction.OnResume)
        assertEquals(1, discoveries)
    }

    @Test
    fun `with access granted the screen searches once, in either order of opening`() = runTest {
        val searchFirst = AddServerViewModel(repository, appPreferences, FakeLocalNetworkAccess())
        searchFirst.discoverServers()
        searchFirst.onAction(AddServerAction.OnResume)
        assertEquals(1, discoveries)
        assertFalse(searchFirst.state.value.localNetworkDenied)

        discoveries = 0
        val resumeFirst = AddServerViewModel(repository, appPreferences, FakeLocalNetworkAccess())
        resumeFirst.onAction(AddServerAction.OnResume)
        resumeFirst.discoverServers()
        assertEquals(1, discoveries)
    }

    @Test
    fun `access that is taken away again shows the notice again`() = runTest {
        val access = FakeLocalNetworkAccess(denied = false)
        val viewModel = AddServerViewModel(repository, appPreferences, access)
        viewModel.discoverServers()

        access.denied = true
        viewModel.onAction(AddServerAction.OnResume)

        assertTrue(viewModel.state.value.localNetworkDenied)
        assertEquals(1, discoveries)
    }

    @Test
    fun `a local address that fails while access is denied names the permission`() = runTest {
        val viewModel =
            AddServerViewModel(repository, appPreferences, FakeLocalNetworkAccess(denied = true))

        viewModel.onAction(AddServerAction.OnConnectClick("10.0.2.2:8096"))

        assertEquals(SetupR.string.ce_add_server_error_local_network, errorOf(viewModel))
        assertTrue(viewModel.state.value.localNetworkDenied)
        assertFalse(viewModel.state.value.isLoading)

        viewModel.onAction(AddServerAction.OnAddressChange)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `the error that names the permission goes when access returns`() = runTest {
        val access = FakeLocalNetworkAccess(denied = true)
        val viewModel = AddServerViewModel(repository, appPreferences, access)
        viewModel.onAction(AddServerAction.OnConnectClick("10.0.2.2:8096"))
        assertEquals(SetupR.string.ce_add_server_error_local_network, errorOf(viewModel))

        access.denied = false
        viewModel.onAction(AddServerAction.OnResume)

        assertNull(viewModel.state.value.error)
        assertEquals(1, discoveries)
    }

    @Test
    fun `another error stays when access returns`() = runTest {
        val access = FakeLocalNetworkAccess(denied = true)
        val viewModel = AddServerViewModel(repository, appPreferences, access)
        viewModel.discoverServers()
        viewModel.onAction(AddServerAction.OnConnectClick("8.8.8.8:8096"))

        access.denied = false
        viewModel.onAction(AddServerAction.OnResume)

        assertEquals(SetupR.string.add_server_error_not_found, errorOf(viewModel))
    }

    @Test
    fun `an internet address that fails while access is denied keeps server not found`() = runTest {
        val viewModel =
            AddServerViewModel(repository, appPreferences, FakeLocalNetworkAccess(denied = true))

        viewModel.onAction(AddServerAction.OnConnectClick("8.8.8.8:8096"))

        assertEquals(SetupR.string.add_server_error_not_found, errorOf(viewModel))
    }

    @Test
    fun `a local address that fails with access granted keeps server not found`() = runTest {
        val viewModel =
            AddServerViewModel(repository, appPreferences, FakeLocalNetworkAccess(denied = false))

        viewModel.onAction(AddServerAction.OnConnectClick("10.0.2.2:8096"))

        assertEquals(SetupR.string.add_server_error_not_found, errorOf(viewModel))
        assertFalse(viewModel.state.value.localNetworkDenied)
    }
}
