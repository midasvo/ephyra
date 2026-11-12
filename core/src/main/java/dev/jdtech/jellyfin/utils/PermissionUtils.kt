package dev.jdtech.jellyfin.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.jdtech.jellyfin.settings.domain.models.Permission

/** Returns whether the given runtime permission is currently granted. */
fun isPermissionGranted(context: Context, permission: Permission): Boolean =
    context.checkSelfPermission(permission.id) == PackageManager.PERMISSION_GRANTED

/** Returns whether the system would show a rationale dialog; only meaningful after a denial. */
fun shouldShowPermissionRationale(activity: Activity, permission: Permission): Boolean =
    activity.shouldShowRequestPermissionRationale(permission.id)

/**
 * CE (#372): whether starting a download should ask for the notification permission. Only on
 * Android 13 and later, and only while it is not granted. Android stops showing the dialog itself
 * after the second denial, so this asks at most twice in the life of the install.
 */
fun shouldRequestNotificationPermission(sdkInt: Int, granted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted

/** Opens the system settings page of this app, e.g. to grant a permanently denied permission. */
fun openAppSettings(context: Context) {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
    context.startActivity(intent)
}
