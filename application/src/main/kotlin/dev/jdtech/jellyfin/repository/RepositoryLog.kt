package dev.jdtech.jellyfin.repository

/**
 * #966: what the repository reports (a report sent, a server's answer it could not read). This
 * module has no logger (it is pure Kotlin); the app passes one that writes to Timber, as the
 * repository did before it moved here (`TimberRepositoryLog` in `core`).
 */
interface RepositoryLog {
    fun debug(message: String)

    fun warn(error: Throwable?, message: String)

    fun error(error: Throwable, message: String? = null)

    companion object {
        val NONE =
            object : RepositoryLog {
                override fun debug(message: String) = Unit

                override fun warn(error: Throwable?, message: String) = Unit

                override fun error(error: Throwable, message: String?) = Unit
            }
    }
}
