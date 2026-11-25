package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerException

// #771: the fake passes every suite, as the Jellyfin adapter does (`Jellyfin*ContractTest` in
// server/jellyfin). The suites are what keep the fake honest. The `WithoutFeatures` classes are a
// server type without the optional features: the capability-gated calls give nothing.

/** The fakes the suites ask for, for a server with [capabilities]. */
private fun fakes(capabilities: ServerCapabilities) =
    object {
        fun server(): MediaServer = FakeMediaServer(capabilities = capabilities)

        fun failing(): MediaServer =
            FakeMediaServer(capabilities = capabilities).apply {
                failWith(ServerException.HttpStatus(500))
            }

        // A refused connection, as an adapter reports it (see `isUnanswered`).
        fun unreachable(): MediaServer =
            FakeMediaServer(capabilities = capabilities).apply {
                failWith(ServerException.Timeout())
            }

        fun stalling(): MediaServer = FakeMediaServer(capabilities = capabilities).apply { stall() }
    }

class FakeMediaServerItemContractTest : ItemContract() {
    private val fakes = fakes(ServerCapabilities.ALL)

    override fun server() = fakes.server()

    override fun failingServer() = fakes.failing()

    override fun unreachableServer() = fakes.unreachable()

    override fun stallingServer() = fakes.stalling()
}

class FakeMediaServerItemWithoutFeaturesContractTest : ItemContract() {
    private val fakes = fakes(NONE)

    override fun server() = fakes.server()

    override fun failingServer() = fakes.failing()

    override fun unreachableServer() = fakes.unreachable()

    override fun stallingServer() = fakes.stalling()
}

class FakeMediaServerItemListContractTest : ItemListContract() {
    private val fakes = fakes(ServerCapabilities.ALL)

    override fun server() = fakes.server()

    override fun failingServer() = fakes.failing()

    override fun unreachableServer() = fakes.unreachable()

    override fun stallingServer() = fakes.stalling()
}

class FakeMediaServerItemListWithoutFeaturesContractTest : ItemListContract() {
    private val fakes = fakes(NONE)

    override fun server() = fakes.server()

    override fun failingServer() = fakes.failing()

    override fun unreachableServer() = fakes.unreachable()

    override fun stallingServer() = fakes.stalling()
}

class FakeMediaServerItemExtrasContractTest : ItemExtrasContract() {
    private val fakes = fakes(ServerCapabilities.ALL)

    override fun server() = fakes.server()

    override fun failingServer() = fakes.failing()

    override fun unreachableServer() = fakes.unreachable()

    override fun stallingServer() = fakes.stalling()
}

class FakeMediaServerItemExtrasWithoutFeaturesContractTest : ItemExtrasContract() {
    private val fakes = fakes(NONE)

    override fun server() = fakes.server()

    override fun failingServer() = fakes.failing()

    override fun unreachableServer() = fakes.unreachable()

    override fun stallingServer() = fakes.stalling()
}

class FakeMediaServerServerInfoContractTest : ServerInfoContract() {
    private val fakes = fakes(ServerCapabilities.ALL)

    override fun server() = fakes.server()

    override fun failingServer() = fakes.failing()

    override fun unreachableServer() = fakes.unreachable()

    override fun stallingServer() = fakes.stalling()
}

class FakeMediaServerServerInfoWithoutFeaturesContractTest : ServerInfoContract() {
    private val fakes = fakes(NONE)

    override fun server() = fakes.server()

    override fun failingServer() = fakes.failing()

    override fun unreachableServer() = fakes.unreachable()

    override fun stallingServer() = fakes.stalling()
}

class FakeMediaServerUserDataContractTest : UserDataContract() {
    private val fakes = fakes(ServerCapabilities.ALL)

    override fun server() = fakes.server()

    override fun failingServer() = fakes.failing()

    override fun unreachableServer() = fakes.unreachable()

    override fun stallingServer() = fakes.stalling()
}

class FakeMediaServerPlaybackContractTest : PlaybackContract() {
    private val fakes = fakes(ServerCapabilities.ALL)

    override fun server() = fakes.server()

    override fun failingServer() = fakes.failing()

    override fun unreachableServer() = fakes.unreachable()

    override fun stallingServer() = fakes.stalling()
}

class FakeMediaServerPlaybackWithoutFeaturesContractTest : PlaybackContract() {
    private val fakes = fakes(NONE)

    override fun server() = fakes.server()

    override fun failingServer() = fakes.failing()

    override fun unreachableServer() = fakes.unreachable()

    override fun stallingServer() = fakes.stalling()
}

class FakeServerConnectorContractTest : ServerConnectorContract() {
    override fun connector(): ServerConnector =
        FakeServerConnector(failingAddresses = setOf(FAILING_ADDRESS))

    override val address = ContractLibrary.address
    override val failingAddress = FAILING_ADDRESS
    override val unreachableAddress = "http://nothing.here"
}

class FakeServerConnectorWithoutFeaturesContractTest : ServerConnectorContract() {
    override fun connector(): ServerConnector =
        FakeServerConnector(capabilities = NONE, failingAddresses = setOf(FAILING_ADDRESS))

    override val address = ContractLibrary.address
    override val failingAddress = FAILING_ADDRESS
    override val unreachableAddress = "http://nothing.here"
}

private const val FAILING_ADDRESS = "http://failing.server"

private val NONE =
    ServerCapabilities(
        quickConnect = false,
        publicUsers = false,
        loginDisclaimer = false,
        mediaSegments = false,
        trickplay = false,
        themeSongs = false,
        similarItems = false,
        extras = false,
        itemCollections = false,
        serverResumeThresholds = false,
        autoPlayNextSetting = false,
        sessionSocket = false,
        cappedDownloadTranscode = false,
    )
