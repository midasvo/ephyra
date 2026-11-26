package dev.jdtech.jellyfin

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#1045): the merged manifest of the TV app holds what the TV app uses and nothing a library
 * adds on its own. A store reviews the permissions of an app, and a permission that is asked for
 * and never used is a reason to reject it.
 *
 * When this fails after a dependency was added or updated: decide whether the TV app needs the new
 * entry. If it does, add it here with the reason. If not, remove it in
 * `app/tv/src/main/AndroidManifest.xml` with `tools:node="remove"`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TvManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    // MATCH_DISABLED_COMPONENTS: WorkManager declares its boot receiver disabled and switches it on
    // at run time, so without the flag it would be absent here while still in the manifest.
    @Suppress("DEPRECATION")
    private val info =
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.MATCH_DISABLED_COMPONENTS,
        )

    @Test
    fun `the app asks only for the permissions it uses`() {
        val expected =
            setOf(
                // The server, and a server on the local network (Android 17).
                "android.permission.INTERNET",
                "android.permission.ACCESS_LOCAL_NETWORK",
                // NetworkConnectivityImpl in `device`, and WorkManager's network constraint.
                "android.permission.ACCESS_NETWORK_STATE",
                // The Watch Next row.
                "com.android.providers.tv.permission.WRITE_EPG_DATA",
                // androidx.core's own permission for receivers that are not exported.
                "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            )

        assertEquals(expected, info.requestedPermissions.orEmpty().toSet())
    }

    @Test
    fun `WorkManager's foreground service, adb dump and boot receiver are left out`() {
        val components =
            info.services.orEmpty().map { it.name } + info.receivers.orEmpty().map { it.name }

        for (name in
            listOf(
                "androidx.work.impl.foreground.SystemForegroundService",
                "androidx.work.impl.diagnostics.DiagnosticsReceiver",
                // The app does not start at boot: the sync is scheduled again when it is opened.
                "androidx.work.impl.background.systemalarm.RescheduleReceiver",
            )) {
            assertTrue(name !in components, message = "$name must not be in the TV manifest")
        }
        // The job service is what runs the Watch Next sync: removing it would be a mistake.
        assertTrue("androidx.work.impl.background.systemjob.SystemJobService" in components)
    }
}
