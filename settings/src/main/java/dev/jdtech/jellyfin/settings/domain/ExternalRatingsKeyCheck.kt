package dev.jdtech.jellyfin.settings.domain

/** CE (#331): what the Test button of the MDBList key says. */
enum class ExternalRatingsKeyStatus {
    VALID,
    INVALID,
    RATE_LIMITED,
    UNREACHABLE,
}

/**
 * CE (#331): asks MDBList whether a key works, for the settings page; the only place where an
 * MDBList error is shown. The data module implements it.
 */
fun interface ExternalRatingsKeyCheck {
    suspend fun check(apiKey: String): ExternalRatingsKeyStatus
}
