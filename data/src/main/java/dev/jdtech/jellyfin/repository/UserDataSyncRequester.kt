package dev.jdtech.jellyfin.repository

/**
 * Asks for the user data that is marked `toBeSynced` to be sent to the server. The repositories
 * call it when a write could not reach the server, so the progress does not wait for the next app
 * start. The app provides the implementation; the default does nothing.
 */
fun interface UserDataSyncRequester {
    fun request()

    companion object {
        val NONE = UserDataSyncRequester {}
    }
}
