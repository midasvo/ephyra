package dev.jdtech.jellyfin.setup.data

import android.app.Application
import android.content.Context
import android.provider.Settings
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#425): adding a server that is already saved keeps the API client signed in as that server's
 * current user. It used to clear the token, so every request failed with 401 until a restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AddSavedServerSessionTest {
    private val servers = mutableMapOf<String, Server>()
    private val addresses = mutableListOf<ServerAddress>()
    private val users = mutableMapOf<UUID, User>()

    private val database =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getServer" -> servers[args[0] as String]
                "getServerWithAddresses" -> {
                    val server = servers.getValue(args[0] as String)
                    ServerWithAddresses(
                        server = server,
                        addresses = addresses.filter { it.serverId == server.id },
                        user = server.currentUserId?.let { users[it] },
                    )
                }
                "getServerCurrentUser" ->
                    servers[args[0] as String]?.currentUserId?.let { users[it] }
                "insertServer" -> {
                    val server = args[0] as Server
                    servers[server.id] = server
                    Unit
                }
                "insertServerAddress" -> {
                    addresses += args[0] as ServerAddress
                    Unit
                }
                "toString" -> "FakeServerDatabaseDao"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ServerDatabaseDao

    private val context: Context by lazy {
        RuntimeEnvironment.getApplication().also {
            // The SDK names the device after it; Robolectric has none.
            Settings.Secure.putString(it.contentResolver, Settings.Secure.ANDROID_ID, "test")
        }
    }

    private val jellyfinApi by lazy { JellyfinApi(context) }

    private val repository by lazy {
        SetupRepositoryImpl(
            jellyfinApi,
            database,
            AppPreferences(context.getSharedPreferences("test", Context.MODE_PRIVATE)),
        )
    }

    private fun saveServer(id: String, address: String, user: User?) {
        val serverAddress = ServerAddress(UUID.randomUUID(), id, address)
        servers[id] = Server(id, "Server $id", serverAddress.id, user?.id)
        addresses += serverAddress
        user?.let { users[it.id] = it }
    }

    private fun signInClient(address: String, user: User) {
        jellyfinApi.api.update(baseUrl = address, accessToken = user.accessToken)
        jellyfinApi.userId = user.id
    }

    private fun found(id: String, address: String) =
        RecommendedServerInfo(
            address = address,
            responseTime = 10,
            score = RecommendedServerInfoScore.GREAT,
            issues = emptyList(),
            systemInfo = Result.success(PublicSystemInfo(id = id, serverName = "Server $id")),
        )

    private val alice = User(UUID.randomUUID(), "alice", serverId = "a", accessToken = "token-a")
    private val bob = User(UUID.randomUUID(), "bob", serverId = "b", accessToken = "token-b")

    @Test
    fun `adding the current server again keeps its user signed in`() = runBlocking {
        saveServer("a", "https://a.example/", alice)
        signInClient("https://a.example/", alice)

        repository.saveServerInDatabase(found("a", "https://a.example/"))

        assertEquals("https://a.example/", jellyfinApi.api.baseUrl)
        assertEquals("token-a", jellyfinApi.api.accessToken)
        assertEquals(alice.id, jellyfinApi.userId)
    }

    @Test
    fun `adding the current server by another address keeps its user signed in`() = runBlocking {
        saveServer("a", "https://a.example/", alice)
        signInClient("https://a.example/", alice)

        repository.saveServerInDatabase(found("a", "http://a.local:8096"))

        assertEquals("http://a.local:8096", jellyfinApi.api.baseUrl)
        assertEquals("token-a", jellyfinApi.api.accessToken)
        assertEquals(alice.id, jellyfinApi.userId)
        assertEquals(2, addresses.count { it.serverId == "a" })
    }

    @Test
    fun `adding another saved server again signs in as that server's user only`() = runBlocking {
        saveServer("a", "https://a.example/", alice)
        saveServer("b", "https://b.example/", bob)
        signInClient("https://a.example/", alice)

        repository.saveServerInDatabase(found("b", "https://b.example/"))

        assertEquals("https://b.example/", jellyfinApi.api.baseUrl)
        assertEquals("token-b", jellyfinApi.api.accessToken)
        assertEquals(bob.id, jellyfinApi.userId)
    }

    @Test
    fun `adding a new server leaves the client without a user`() = runBlocking {
        saveServer("a", "https://a.example/", alice)
        signInClient("https://a.example/", alice)

        repository.saveServerInDatabase(found("c", "https://c.example/"))

        assertEquals("https://c.example/", jellyfinApi.api.baseUrl)
        assertNull(jellyfinApi.api.accessToken)
        assertNull(jellyfinApi.userId)
        assertNull(servers.getValue("c").currentUserId)
    }
}
