package dev.jdtech.jellyfin.settings.presentation.settings

import android.content.Context
import android.content.pm.PackageManager

/**
 * CE (#626): whether this build has Google Cast in it. Only the `proprietary` flavor of the phone
 * app does; the `libre` flavor has no Google code at all and must not show settings for it.
 *
 * The settings module cannot ask `:player:cast` (which depends on it), so it reads what the Cast
 * SDK itself needs to be there: the options provider that `:player:cast`'s proprietary manifest
 * declares.
 */
object CastBuild {
    private const val OPTIONS_PROVIDER =
        "com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"

    fun isIncluded(context: Context): Boolean =
        try {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.containsKey(OPTIONS_PROVIDER) == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
