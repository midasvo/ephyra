package dev.jdtech.jellyfin.ratings

import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.ExternalRatingsKeyCheck
import dev.jdtech.jellyfin.settings.domain.ExternalRatingsKeyStatus
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * CE (#331): the ratings of the detail pages from MDBList, behind the switch and the user's key.
 *
 * - Off, or no key: no request, no cache read, no ratings. The switch and the key are read again
 *   right before a request.
 * - Concurrent lookups of one title share one request.
 * - A title's answer is kept for [FOUND_TTL_MS] ([EMPTY_TTL_MS] when MDBList had nothing for it).
 * - A failed request (no answer, timeout, 5xx, bad body) isn't repeated for that title for
 *   [FAILURE_BACKOFF_MS]. A 429 stops every request for `Retry-After` seconds ([DEFAULT_BACKOFF_MS]
 *   when it is missing). A key MDBList turns down isn't used again until it changes or the Test
 *   button finds it works.
 * - Whenever a request isn't made or fails, older cached ratings are still shown.
 *
 * The rejected key and the back-offs live in memory only: a restart tries once more.
 */
class MdbListRatings(
    private val appPreferences: AppPreferences,
    private val client: MdbListClient,
    private val cache: ExternalRatingsCache,
    private val clock: () -> Long = System::currentTimeMillis,
) : ExternalRatingsSource, ExternalRatingsKeyCheck {
    @Volatile private var rejectedKey: String? = null
    @Volatile private var blockedUntil = 0L
    private val failedAt = ConcurrentHashMap<String, Long>()
    /** Titles this process has seen ratings for, in the cache or from MDBList. Memory only. */
    private val rated = ConcurrentHashMap.newKeySet<String>()
    /** One lookup per title at a time, so two pages share a request and a cache write. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** The key while switched on with one, rejected or not; null otherwise. */
    private fun key(): String? {
        if (!appPreferences.getValue(appPreferences.externalRatings)) return null
        return appPreferences.getValue(appPreferences.mdbListApiKey)?.trim()?.ifEmpty { null }
    }

    /** The key to send now, or null when nothing may be sent. */
    private fun sendableKey(lookup: RatingsLookup): String? {
        val key = key() ?: return null
        val now = clock()
        if (key == rejectedKey || now < blockedUntil || recentlyFailed(lookup, now)) return null
        return key
    }

    private fun recentlyFailed(lookup: RatingsLookup, now: Long): Boolean =
        failedAt[lookup.key]?.let { now - it < FAILURE_BACKOFF_MS } == true

    override fun isOn(): Boolean = key() != null

    override fun canRequest(lookup: RatingsLookup): Boolean = sendableKey(lookup) != null

    /**
     * Room is kept when MDBList may be asked, or when the title had ratings earlier in this
     * process: [ratings] then shows those cached ones even while it may not ask.
     */
    override fun mightHaveRatings(lookup: RatingsLookup): Boolean =
        isOn() && (canRequest(lookup) || lookup.key in rated)

    override suspend fun ratings(lookup: RatingsLookup): List<ExternalRating> {
        if (!isOn()) return emptyList()
        return locks.getOrPut(lookup.key) { Mutex() }.withLock { lookUp(lookup) }
    }

    private suspend fun lookUp(lookup: RatingsLookup): List<ExternalRating> {
        val cached = withContext(Dispatchers.IO) { cache.read(lookup) }
        if (cached != null && cached.ratings.isNotEmpty()) rated += lookup.key
        val now = clock()
        if (cached != null) {
            val ttl = if (cached.ratings.isEmpty()) EMPTY_TTL_MS else FOUND_TTL_MS
            if (now - cached.fetchedAt in 0 until ttl) return cached.ratings
        }
        val stale = cached?.ratings.orEmpty()
        // Read again right before the request: the switch or the key may have changed while the
        // cache was read. A rejected key, a 429 wait or a recent failure send nothing either.
        val key = sendableKey(lookup) ?: return stale

        return when (val result = client.title(lookup, key)) {
            is MdbListClient.Result.Found -> {
                failedAt.remove(lookup.key)
                withContext(Dispatchers.IO) { cache.write(lookup, result.ratings, clock()) }
                if (result.ratings.isNotEmpty()) rated += lookup.key else rated -= lookup.key
                result.ratings
            }
            MdbListClient.Result.NotFound -> {
                failedAt.remove(lookup.key)
                withContext(Dispatchers.IO) { cache.write(lookup, emptyList(), clock()) }
                rated -= lookup.key
                emptyList()
            }
            MdbListClient.Result.InvalidKey -> {
                rejectedKey = key
                stale
            }
            is MdbListClient.Result.RateLimited -> {
                backOff(result.retryAfterSeconds)
                stale
            }
            MdbListClient.Result.Failed -> {
                failedAt[lookup.key] = clock()
                stale
            }
        }
    }

    /** One request to `/user`, also while a 429 is waited out: the user pressed the button. */
    override suspend fun check(apiKey: String): ExternalRatingsKeyStatus {
        val key = apiKey.trim()
        if (key.isEmpty()) return ExternalRatingsKeyStatus.INVALID
        return when (val result = client.user(key)) {
            is MdbListClient.Result.Found -> {
                if (rejectedKey == key) rejectedKey = null
                ExternalRatingsKeyStatus.VALID
            }
            MdbListClient.Result.InvalidKey -> {
                rejectedKey = key
                ExternalRatingsKeyStatus.INVALID
            }
            is MdbListClient.Result.RateLimited -> {
                backOff(result.retryAfterSeconds)
                ExternalRatingsKeyStatus.RATE_LIMITED
            }
            // /user has no "not found"; anything else means MDBList didn't answer as documented.
            MdbListClient.Result.NotFound,
            MdbListClient.Result.Failed -> ExternalRatingsKeyStatus.UNREACHABLE
        }
    }

    private fun backOff(retryAfterSeconds: Long?) {
        val wait =
            retryAfterSeconds?.coerceAtMost(MAX_BACKOFF_SECONDS)?.times(1000) ?: DEFAULT_BACKOFF_MS
        blockedUntil = maxOf(blockedUntil, clock() + wait)
    }

    companion object {
        const val FOUND_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val EMPTY_TTL_MS = 24L * 60 * 60 * 1000
        const val FAILURE_BACKOFF_MS = 15L * 60 * 1000
        const val DEFAULT_BACKOFF_MS = 60L * 60 * 1000
        /** The daily limit resets within a day; a larger Retry-After is a mistake. */
        private const val MAX_BACKOFF_SECONDS = 24L * 60 * 60
    }
}
