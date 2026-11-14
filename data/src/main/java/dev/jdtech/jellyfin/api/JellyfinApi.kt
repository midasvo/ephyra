package dev.jdtech.jellyfin.api

import android.content.Context
import dev.jdtech.jellyfin.data.BuildConfig
import dev.jdtech.jellyfin.settings.domain.Constants
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
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
     * connection pool. The player streams media over it ([httpClient]) and so reuses the connection
     * the API calls before playback left open.
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

    /**
     * CE (#281): told how each request of the app goes. The reachability banner follows these, so
     * no request is made to learn that the server is there. Called on the request's own thread.
     */
    @Volatile var requestWatcher: RequestWatcher? = null

    /** The probe has a short limit of its own and is not one of the requests that are watched. */
    private val probeClient by lazy {
        okHttpFactory.base
            .newBuilder()
            .apply { interceptors().clear() }
            // A redirect is an answer already; the probe goes nowhere the server sends it.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * CE (#281): asks the server in use for its public info, the smallest request it answers
     * without a token. True when it answered, false when it did not within [PROBE_TIMEOUT_MS] or a
     * proxy answered for it, null when no server is set.
     */
    suspend fun probeServer(): Boolean? {
        val baseUrl = api.baseUrl?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val request =
            try {
                Request.Builder().url("$baseUrl/System/Info/Public").build()
            } catch (e: IllegalArgumentException) {
                // An address OkHttp cannot parse: nothing to reach.
                return false
            }
        // Enqueued, so a caller that stops waiting cancels the call instead of blocking on it.
        return suspendCancellableCoroutine { continuation ->
            val call = probeClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resume(false)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val answered = response.use { it.code < HTTP_SERVER_ERROR }
                        if (continuation.isActive) continuation.resume(answered)
                    }
                }
            )
        }
    }

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
        val watcher = requestWatcher
        watcher?.onStarted()
        val response =
            try {
                chain.proceed(request)
            } catch (e: IOException) {
                // CE (#281): a request the app gave up on itself says nothing about the server.
                if (chain.call().isCanceled()) {
                    watcher?.onCancelled()
                } else {
                    serverReturnDetector.onFailure()
                    watcher?.onUnanswered()
                }
                throw e
            } catch (e: RuntimeException) {
                // Not about the server, but every start gets its end.
                watcher?.onCancelled()
                throw e
            }
        if (serverReturnDetector.onResponse(response.code)) _serverReturned.tryEmit(Unit)
        if (response.code < HTTP_SERVER_ERROR) watcher?.onAnswered() else watcher?.onUnanswered()
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

        /**
         * CE (#281): how long the reachability probe waits. Short on purpose: a probe that waits
         * out the connect timeout is the hang the banner is there to avoid.
         */
        const val PROBE_TIMEOUT_MS = 3_000L
        private const val HTTP_SERVER_ERROR = 500

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
