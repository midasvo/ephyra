package dev.jdtech.jellyfin.setup.presentation

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.models.Server
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#404): the TV Add server screen passes the field as typed, so an address with spaces around
 * it failed with "Server not found". The ViewModel trims it, for the phone and TV alike.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AddServerAddressTrimTest {
    private val addedAddresses = mutableListOf<String>()

    private val repository =
        Proxy.newProxyInstance(
            SetupRepository::class.java.classLoader,
            arrayOf(SetupRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "addServer" -> {
                    addedAddresses.add(args[0] as String)
                    Server(
                        id = "s",
                        name = "Demo",
                        currentServerAddressId = null,
                        currentUserId = null,
                    )
                }
                "toString" -> "FakeSetupRepository"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SetupRepository

    private val appPreferences =
        AppPreferences(
            RuntimeEnvironment.getApplication()
                .getSharedPreferences("add-server-trim-test", Context.MODE_PRIVATE)
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
    fun `an address with spaces around it reaches the repository trimmed`() = runTest {
        val viewModel = AddServerViewModel(repository, appPreferences, FakeLocalNetworkAccess())

        viewModel.onAction(AddServerAction.OnConnectClick("  demo.jellyfin.org/stable  "))

        assertEquals(listOf("demo.jellyfin.org/stable"), addedAddresses)
        assertEquals("s", appPreferences.getValue(appPreferences.currentServer))
    }
}
