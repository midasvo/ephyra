package dev.jdtech.jellyfin.settings.presentation.settings

/**
 * CE (#626): whether this build includes Google Cast. Only the `proprietary` flavor of the phone
 * app does; `libre` and `app/tv` have no Google code at all and must not show settings for it.
 *
 * Bound per app/flavor via Hilt so `:settings` has no runtime dependency on Cast or GMS strings.
 */
fun interface CastBuild {
    fun isIncluded(): Boolean

    companion object {
        val None = CastBuild { false }
    }
}
