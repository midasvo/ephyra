package dev.jdtech.jellyfin.api

import android.content.Context
import dev.jdtech.jellyfin.data.BuildConfig
import dev.jdtech.jellyfin.settings.domain.Constants
import java.io.IOException
import java.util.UUID
import kotlin.time.DurationUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.brandingApi
import org.jellyfin.sdk.api.client.extensions.deviceApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.suggestionApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.trickPlayApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import timber.log.Timber

/**
 * Jellyfin API class using org.jellyfin.sdk:jellyfin-core
 *
 * @param androidContext The context
 * @param socketTimeout The socket timeout
 * @constructor Creates a new [JellyfinApi] instance
 */
class JellyfinApi(
    androidContext: Context,
    requestTimeout: Long = Constants.NETWORK_DEFAULT_REQUEST_TIMEOUT,
    connectTimeout: Long = Constants.NETWORK_DEFAULT_CONNECT_TIMEOUT,
    socketTimeout: Long = Constants.NETWORK_DEFAULT_SOCKET_TIMEOUT,
) {
    /**
     * CE (#296): the SDK builds its client from this one with `newBuilder()`, so both share one
     * connection pool. The player streams media over it ([httpClient]) and so reuses the
     * connection the API calls before playback left open.
     */
    private val okHttpFactory =
        OkHttpFactory(base = OkHttpClient.Builder().addInterceptor(::watchResponses).build())

    private val _unauthorized =
        MutableSharedFlow<String>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * CE (#334): tokens the server answered 401 to while they were the client's current token, so
     * possibly revoked on the server (signed out from the dashboard, the device deleted). The
     * session keeper checks and signs out.
     */
    val unauthorized: SharedFlow<String> = _unauthorized.asSharedFlow()

    private val serverReturnDetector = ServerReturnDetector()
    private val _serverReturned =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * CE (#552): emits when the server answers again after requests that could not reach it. The
     * user data sync uses it to send what is waiting without sitting out its retry delay.
     */
    val serverReturned: SharedFlow<Unit> = _serverReturned.asSharedFlow()

    /** The OkHttp client the API's clients are built from. Derive from it with `newBuilder()`. */
    val httpClient: OkHttpClient
        get() = okHttpFactory.base

    val jellyfin = createJellyfin {
        clientInfo =
            ClientInfo(
                name =
                    androidContext.applicationInfo
                        .loadLabel(androidContext.packageManager)
                        .toString(),
                // CE (#386): the installed app's version, as Settings > About shows it. This
                // module's BuildConfig carries upstream's Versions.APP_NAME.
                version =
                    androidContext.packageManager
                        .getPackageInfo(androidContext.packageName, 0)
                        .versionName ?: "unknown",
            )
        context = androidContext
        deviceInfo = buildDeviceInfo(androidContext)
        apiClientFactory = okHttpFactory
        socketConnectionFactory = okHttpFactory
    }
    val api =
        jellyfin.createApi(
            httpClientOptions =
                HttpClientOptions(
                    requestTimeout = requestTimeout.toDuration(DurationUnit.MILLISECONDS),
                    connectTimeout = connectTimeout.toDuration(DurationUnit.MILLISECONDS),
                    socketTimeout = socketTimeout.toDuration(DurationUnit.MILLISECONDS),
                )
        )
    var userId: UUID? = null

    private fun watchResponses(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response =
            try {
                chain.proceed(request)
            } catch (e: IOException) {
                serverReturnDetector.onFailure()
                throw e
            }
        if (serverReturnDetector.onResponse(response.code)) _serverReturned.tryEmit(Unit)
        detectRevokedToken(request, response)
        return response
    }

    private fun detectRevokedToken(request: Request, response: Response) {
        if (response.code != 401) return
        val token = api.accessToken ?: return
        val sentToken =
            request.header("Authorization")?.contains(token) == true ||
                request.url.queryParameter("api_key") == token ||
                request.url.queryParameter("ApiKey") == token
        if (sentToken) _unauthorized.tryEmit(token)
    }

    /**
     * CE (#334): signs [accessToken] out on the server (`POST /Sessions/Logout`), which ends its
     * session and revokes the token. Only for a token the app is throwing away: a removed user or
     * server. Best-effort: false when the server could not be reached in time.
     */
    suspend fun endSession(baseUrl: String, accessToken: String): Boolean =
        withContext(Dispatchers.IO) {
            val client =
                jellyfin.createApi(
                    baseUrl = baseUrl,
                    accessToken = accessToken,
                    httpClientOptions =
                        HttpClientOptions(
                            requestTimeout = END_SESSION_TIMEOUT,
                            connectTimeout = END_SESSION_TIMEOUT,
                            socketTimeout = END_SESSION_TIMEOUT,
                        ),
                )
            try {
                withTimeoutOrNull(END_SESSION_TIMEOUT) { client.sessionApi.reportSessionEnded() } !=
                    null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not end the session on the server")
                false
            }
        }

    val brandingApi = api.brandingApi
    val authenticationApi = api.authenticationApi
    val deviceApi = api.deviceApi
    val libraryApi = api.libraryApi
    val mediaInfoApi = api.mediaInfoApi
    val mediaSegmentApi = api.mediaSegmentApi
    val sessionApi = api.sessionApi
    val showApi = api.showApi
    val suggestionApi = api.suggestionApi
    val systemApi = api.systemApi
    val trickPlayApi = api.trickPlayApi
    val userApi = api.userApi
    val userDataApi = api.userDataApi
    val videoApi = api.videoApi
    val userViewApi = api.userViewApi

    /**
     * Android gives apps with the same signing key the same ID, and a login makes Jellyfin end the
     * user's other sessions on that ID. Release and staging share a key, so without the suffix
     * logging in to one logs the other out. Release keeps the plain ID its tokens are stored under.
     */
    private fun buildDeviceInfo(context: Context): DeviceInfo {
        val device = androidDevice(context)
        return if (BuildConfig.BUILD_TYPE == "release") {
            device
        } else {
            device.copy(id = "${device.id}-${BuildConfig.BUILD_TYPE}")
        }
    }

    companion object {
        private val END_SESSION_TIMEOUT = 5.seconds

        @Volatile private var INSTANCE: JellyfinApi? = null

        fun getInstance(
            context: Context,
            requestTimeout: Long = Constants.NETWORK_DEFAULT_REQUEST_TIMEOUT,
            connectTimeout: Long = Constants.NETWORK_DEFAULT_CONNECT_TIMEOUT,
            socketTimeout: Long = Constants.NETWORK_DEFAULT_SOCKET_TIMEOUT,
        ): JellyfinApi {
            synchronized(this) {
                var instance = INSTANCE
                if (instance == null) {
                    instance =
                        JellyfinApi(
                            androidContext = context.applicationContext,
                            requestTimeout = requestTimeout,
                            connectTimeout = connectTimeout,
                            socketTimeout = socketTimeout,
                        )
                    INSTANCE = instance
                }
                return instance
            }
        }
    }
}
