package dev.jdtech.jellyfin.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CE (#372): when starting a download asks for the notification permission. */
class NotificationPermissionTest {
    @Test
    fun `asks on Android 13 and later while not granted`() {
        assertTrue(shouldRequestNotificationPermission(sdkInt = 33, granted = false))
        assertTrue(shouldRequestNotificationPermission(sdkInt = 34, granted = false))
        assertTrue(shouldRequestNotificationPermission(sdkInt = 37, granted = false))
    }

    @Test
    fun `does not ask once granted`() {
        assertFalse(shouldRequestNotificationPermission(sdkInt = 33, granted = true))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 36, granted = true))
    }

    @Test
    fun `does not ask below Android 13, where the permission does not exist`() {
        assertFalse(shouldRequestNotificationPermission(sdkInt = 29, granted = false))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 32, granted = false))
    }
}
