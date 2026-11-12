package dev.jdtech.jellyfin.setup.presentation

import android.app.Application
import android.provider.Settings
import com.sun.net.httpserver.HttpServer
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.domain.FakeLocalNetworkAccess
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesAction
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesViewModel
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#390): removing the address in use and adding an address that is already there were dropped
 * without a word, so the dialog closed and nothing happened. The screen now says why.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerAddressesFeedbackTest {
    private val serverId = "server"
    private val inUse = ServerAddress(UUID.randomUUID(), serverId, "http://10.0.2.2:8096")
    private val other = ServerAddress(UUID.randomUUID(), serverId, "http://192.168.1.2:8096")
    private val addresses = Collections.synchronizedList(mutableListOf(inUse, other))
    private val deleted = Collections.synchronizedList(mutableListOf<UUID>())

    // The ViewModel calls only these; suspend functions that do not suspend can return directly.
    private val database =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getServerCurrentAddress" -> inUse
                "deleteServerAddress" -> {
                    deleted.add(args[0] as UUID)
                    addresses.removeAll { it.id == args[0] }
                    Unit
                }
                "getServerWithAddresses" ->
                    ServerWithAddresses(
                        server = Server(serverId, "Ephyra Test", inUse.id, null),
                        addresses = addresses.toList(),
                        user = null,
                    )
                "toString" -> "FakeServerDatabaseDao"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ServerDatabaseDao

    private val localNetworkAccess = FakeLocalNetworkAccess()
    private lateinit var viewModel: ServerAddressesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val application = RuntimeEnvironment.getApplication()
        // The SDK names the device after it; Robolectric has none.
        Settings.Secure.putString(application.contentResolver, Settings.Secure.ANDROID_ID, "test")
        viewModel = ServerAddressesViewModel(application, database, localNetworkAccess)
        viewModel.loadAddresses(serverId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `removing the address in use is refused with a message`() = runBlocking {
        viewModel.onAction(ServerAddressesAction.DeleteAddress(inUse.id))

        val error = withTimeout(5_000) { viewModel.state.first { it.error != null } }.error!!
        val message = error.single() as UiText.StringResource
        assertEquals(SetupR.string.ce_server_address_in_use, message.resId)
        assertTrue(deleted.isEmpty())
        assertEquals(listOf(inUse, other), viewModel.state.value.addresses)
    }

    @Test
    fun `removing another address still removes it, without a message`() = runBlocking {
        viewModel.onAction(ServerAddressesAction.DeleteAddress(other.id))

        withTimeout(5_000) { viewModel.state.first { it.addresses == listOf(inUse) } }
        assertEquals(listOf(other.id), deleted.toList())
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `adding an address that is already there says so`() = runBlocking {
        // A local stand-in for the server: the SDK only reads its public system info.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/System/Info/Public") { exchange ->
            val body =
                """{"Id":"$serverId","ServerName":"Ephyra Test","Version":"10.11.0",""" +
                    """"ProductName":"Jellyfin Server","StartupWizardCompleted":true}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val stubAddress = "http://127.0.0.1:${server.address.port}"
            addresses.add(ServerAddress(UUID.randomUUID(), serverId, stubAddress))

            viewModel.onAction(ServerAddressesAction.AddAddress("$stubAddress/"))

            val error = withTimeout(15_000) { viewModel.state.first { it.error != null } }.error!!
            val message = error.single() as UiText.StringResource
            assertEquals(SetupR.string.ce_server_address_already_added, message.resId)
            assertEquals(3, addresses.size)
        } finally {
            server.stop(0)
        }
    }

    // CE (#376): nothing listens on these, so the SDK finds no server either way.
    @Test
    fun `a local address that fails while access is denied names the permission`() = runBlocking {
        localNetworkAccess.denied = true

        viewModel.onAction(ServerAddressesAction.AddAddress("http://[fd00::1]:9"))

        val error = withTimeout(60_000) { viewModel.state.first { it.error != null } }.error!!
        val message = error.single() as UiText.StringResource
        assertEquals(SetupR.string.ce_add_server_error_local_network, message.resId)
    }

    @Test
    fun `an address that is not local keeps server not found while access is denied`() =
        runBlocking {
            localNetworkAccess.denied = true

            viewModel.onAction(ServerAddressesAction.AddAddress("http://127.0.0.1:9"))

            val error = withTimeout(60_000) { viewModel.state.first { it.error != null } }.error!!
            val message = error.single() as UiText.StringResource
            assertEquals(SetupR.string.add_server_error_not_found, message.resId)
        }

    @Test
    fun `a local address that fails with access granted keeps server not found`() = runBlocking {
        viewModel.onAction(ServerAddressesAction.AddAddress("http://[fd00::1]:9"))

        val error = withTimeout(60_000) { viewModel.state.first { it.error != null } }.error!!
        val message = error.single() as UiText.StringResource
        assertEquals(SetupR.string.add_server_error_not_found, message.resId)
    }
}
