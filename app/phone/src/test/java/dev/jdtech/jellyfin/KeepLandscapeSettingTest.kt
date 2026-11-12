package dev.jdtech.jellyfin

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.KeepPlayerLandscape
import dev.jdtech.jellyfin.settings.presentation.enums.AppVariant
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** CE (#363): "Keep the player in landscape" shows only where the player can get a wide window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeepLandscapeSettingTest {

    private lateinit var context: Context
    private lateinit var appPreferences: AppPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val sp = context.getSharedPreferences("keep_landscape_test", Context.MODE_PRIVATE)
        appPreferences = AppPreferences(sp)
    }

    private fun shown(variant: AppVariant = AppVariant.PHONE): Boolean {
        val viewModel = SettingsViewModel(context, appPreferences, variant)
        viewModel.loadPreferences(listOf(SettingsR.string.settings_category_player))
        return viewModel.state.value.preferenceGroups
            .flatMap { it.preferences }
            .any { it.nameStringResource == SettingsR.string.ce_pref_player_keep_landscape }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `hidden on a phone`() {
        assertFalse(shown())
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp")
    fun `hidden on a phone held in landscape`() {
        assertFalse(shown())
    }

    @Test
    @Config(qualifiers = "w1024dp-h640dp")
    fun `shown on a tablet`() {
        assertTrue(shown())
    }

    @Test
    @Config(qualifiers = "w640dp-h1024dp")
    fun `shown on a tablet held upright`() {
        assertTrue(shown())
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `shown on a folded foldable, which has a hinge sensor`() {
        shadowOf(context.packageManager)
            .setSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE, true)
        assertTrue(shown())
    }

    @Test
    @Config(qualifiers = "w1024dp-h640dp")
    fun `never on TV`() {
        assertFalse(shown(AppVariant.TV))
    }

    @Test
    fun `off by default`() {
        assertFalse(appPreferences.getValue(appPreferences.playerKeepLandscape))
    }

    @Test
    fun `Android 16 and later ignore the request on large screens`() {
        assertTrue(KeepPlayerLandscape.isSupported(35))
        assertFalse(KeepPlayerLandscape.isSupported(36))
        assertFalse(KeepPlayerLandscape.isSupported(37))
    }
}
