package dev.jdtech.jellyfin.setup.presentation.permission

data class PermissionState(
    val currentRequest: PermissionRequest? = null,
    val permanentlyDenied: Boolean = false,
    // CE (#376): the name the system settings list the permission under, when the system tells.
    val systemSettingName: String? = null,
    val dismissedThisSession: Boolean = false,
) {
    /** Whether the upgrader interstitial should be shown before the home screen. */
    val showInterstitial: Boolean
        get() = currentRequest != null && !dismissedThisSession
}
