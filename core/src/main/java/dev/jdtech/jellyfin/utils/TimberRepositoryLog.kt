package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.repository.RepositoryLog
import timber.log.Timber

/**
 * #966: the repository's log in Timber, under the tag its lines had in debug builds before it moved
 * to `application` (DebugTree names the caller's class).
 */
object TimberRepositoryLog : RepositoryLog {
    private const val TAG = "MediaRepositoryImpl"

    override fun debug(message: String) {
        Timber.tag(TAG).d(message)
    }

    override fun warn(error: Throwable?, message: String) {
        Timber.tag(TAG).w(error, message)
    }

    override fun error(error: Throwable, message: String?) {
        if (message == null) Timber.tag(TAG).e(error) else Timber.tag(TAG).e(error, message)
    }
}
