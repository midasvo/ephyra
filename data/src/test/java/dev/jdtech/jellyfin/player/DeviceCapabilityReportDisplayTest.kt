package dev.jdtech.jellyfin.player

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #385: the About screen builds the report from the application context, which has no display.
 * `Context.display` throws for it from API 30 on, so the export failed every time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceCapabilityReportDisplayTest {

    @Test
    fun `builds the report from the application context`() {
        val context = RuntimeEnvironment.getApplication()

        val report =
            DeviceCapabilityReportBuilder.build(
                context = context,
                deviceProfileBuilder = DeviceProfileBuilder(context),
                findroidVersion = "2.4.0",
                findroidBuildType = "debug",
                transcodeDolbyVision = false,
                directPlayDolbyVision = false,
            )

        assertTrue(report.display.widthPx > 0)
        assertTrue(report.display.heightPx > 0)
        assertTrue(report.display.refreshRateHz > 0f)
        assertTrue(report.display.supportedRefreshRatesHz.isNotEmpty())
    }
}
