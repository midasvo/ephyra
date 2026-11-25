package dev.jdtech.jellyfin.server.jellyfin.contract

import android.app.Application
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.player.DeviceCodecProbe
import dev.jdtech.jellyfin.repository.StandInServer
import dev.jdtech.jellyfin.server.jellyfin.JellyfinDeviceProfiles
import dev.jdtech.jellyfin.server.jellyfin.JellyfinMediaServer
import dev.jdtech.jellyfin.server.jellyfin.JellyfinServerConnector
import dev.jdtech.jellyfin.server.jellyfin.toUuid
import dev.jdtech.jellyfin.testing.ContractLibrary
import dev.jdtech.jellyfin.testing.ContractLibrary.Part
import dev.jdtech.jellyfin.testing.ItemContract
import dev.jdtech.jellyfin.testing.ItemExtrasContract
import dev.jdtech.jellyfin.testing.ItemListContract
import dev.jdtech.jellyfin.testing.PlaybackContract
import dev.jdtech.jellyfin.testing.ServerConnectorContract
import dev.jdtech.jellyfin.testing.ServerInfoContract
import dev.jdtech.jellyfin.testing.UserDataContract
import java.io.Closeable
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// #771: the Jellyfin adapter passes the contract suites of `testing`, against the recordings of
// the Docker test server, or against that server itself with EPHYRA_TEST_SERVER (see
// ContractServer). FakeMediaServerContractTest in `testing` runs the same suites on the fake.

/** The adapter's ports over [baseUrl], signed in as the library's user with the token `token`. */
private fun jellyfinAt(baseUrl: String): MediaServer {
    val context = StandInServer.context()
    val api =
        JellyfinApi(context).apply {
            api.update(baseUrl = baseUrl, accessToken = LiveServer.FAKE_TOKEN)
            userId = ContractLibrary.userId.toUuid()
        }
    return JellyfinMediaServer(api, JellyfinDeviceProfiles(DeviceCodecProbe(context)))
}

/**
 * The servers that fail, for the suites' failure tests: no recordings, a stand-in of their own.
 * Closed after each test.
 */
private class FailingServers : Closeable {
    /** Answers every request with HTTP 500. */
    val failing = StandInServer(concurrent = true) { StandInServer.Response(500) }

    /** Takes every request and answers none in time. */
    val stalling =
        StandInServer(concurrent = true) {
            Thread.sleep(60_000)
            StandInServer.Response(500)
        }

    /** A port where nothing listens any more. */
    val unreachable: String = StandInServer().use { it.baseUrl }

    override fun close() {
        failing.close()
        stalling.close()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinItemContractTest : ItemContract() {
    @get:Rule val contract = ContractServer()
    private val servers = FailingServers()

    override val notOnServer: Set<Part>
        get() = contract.notOnServer

    override fun server(): MediaServer = jellyfinAt(contract.baseUrl)

    override fun failingServer(): MediaServer = jellyfinAt(servers.failing.baseUrl)

    override fun unreachableServer(): MediaServer = jellyfinAt(servers.unreachable)

    override fun stallingServer(): MediaServer = jellyfinAt(servers.stalling.baseUrl)

    @After fun stop() = servers.close()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinItemListContractTest : ItemListContract() {
    @get:Rule val contract = ContractServer()
    private val servers = FailingServers()

    override val notOnServer: Set<Part>
        get() = contract.notOnServer

    override fun server(): MediaServer = jellyfinAt(contract.baseUrl)

    override fun failingServer(): MediaServer = jellyfinAt(servers.failing.baseUrl)

    override fun unreachableServer(): MediaServer = jellyfinAt(servers.unreachable)

    override fun stallingServer(): MediaServer = jellyfinAt(servers.stalling.baseUrl)

    @After fun stop() = servers.close()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinItemExtrasContractTest : ItemExtrasContract() {
    @get:Rule val contract = ContractServer()
    private val servers = FailingServers()

    override val notOnServer: Set<Part>
        get() = contract.notOnServer

    override fun server(): MediaServer = jellyfinAt(contract.baseUrl)

    override fun failingServer(): MediaServer = jellyfinAt(servers.failing.baseUrl)

    override fun unreachableServer(): MediaServer = jellyfinAt(servers.unreachable)

    override fun stallingServer(): MediaServer = jellyfinAt(servers.stalling.baseUrl)

    @After fun stop() = servers.close()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinServerInfoContractTest : ServerInfoContract() {
    @get:Rule val contract = ContractServer()
    private val servers = FailingServers()

    override val notOnServer: Set<Part>
        get() = contract.notOnServer

    override fun server(): MediaServer = jellyfinAt(contract.baseUrl)

    override fun failingServer(): MediaServer = jellyfinAt(servers.failing.baseUrl)

    override fun unreachableServer(): MediaServer = jellyfinAt(servers.unreachable)

    override fun stallingServer(): MediaServer = jellyfinAt(servers.stalling.baseUrl)

    @After fun stop() = servers.close()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinUserDataContractTest : UserDataContract() {
    @get:Rule val contract = ContractServer()
    private val servers = FailingServers()

    override fun server(): MediaServer = jellyfinAt(contract.baseUrl)

    override fun failingServer(): MediaServer = jellyfinAt(servers.failing.baseUrl)

    override fun unreachableServer(): MediaServer = jellyfinAt(servers.unreachable)

    override fun stallingServer(): MediaServer = jellyfinAt(servers.stalling.baseUrl)

    @After fun stop() = servers.close()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinPlaybackContractTest : PlaybackContract() {
    @get:Rule val contract = ContractServer()
    private val servers = FailingServers()

    override val notOnServer: Set<Part>
        get() = contract.notOnServer

    override fun server(): MediaServer = jellyfinAt(contract.baseUrl)

    override fun failingServer(): MediaServer = jellyfinAt(servers.failing.baseUrl)

    override fun unreachableServer(): MediaServer = jellyfinAt(servers.unreachable)

    override fun stallingServer(): MediaServer = jellyfinAt(servers.stalling.baseUrl)

    @After fun stop() = servers.close()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinServerConnectorContractTest : ServerConnectorContract() {
    @get:Rule val contract = ContractServer()
    private val servers = FailingServers()

    override fun connector(): ServerConnector =
        JellyfinServerConnector(JellyfinApi(StandInServer.context()))

    override val address: String
        get() = contract.baseUrl

    override val failingAddress: String
        get() = servers.failing.baseUrl

    override val unreachableAddress: String
        get() = servers.unreachable

    @After fun stop() = servers.close()
}
