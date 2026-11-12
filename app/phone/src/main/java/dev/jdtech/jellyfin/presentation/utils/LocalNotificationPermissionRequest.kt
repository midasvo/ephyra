package dev.jdtech.jellyfin.presentation.utils

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CE (#372): asks for the notification permission if it applies (Android 13 and later, not
 * granted). Provided by MainActivity; starting a download calls it.
 */
val LocalNotificationPermissionRequest = staticCompositionLocalOf<() -> Unit> { {} }
