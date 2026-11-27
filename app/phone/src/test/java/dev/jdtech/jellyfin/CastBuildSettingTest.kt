package dev.jdtech.jellyfin

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.presentation.enums.AppVariant
import dev.jdtech.jellyfin.settings.presentation.settings.CastBuild
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsViewModel
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CE (#626): Cast settings show only on phone builds that include Cast. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CastBuildSettingTest {

    private lateinit var context: Context
    private lateinit var appPreferences: AppPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val sp = context.getSharedPreferences("cast_build_test", Context.MODE_PRIVATE)
        appPreferences = AppPreferences(sp)
    }

    private fun shown(variant: AppVariant, castBuild: CastBuild): Boolean {
        val viewModel = SettingsViewModel(context, appPreferences, variant, castBuild = castBuild)
        viewModel.loadPreferences(listOf(SettingsR.string.settings_category_player))
        return viewModel.state.value.preferenceGroups
            .flatMap { it.preferences }
            .any { it.nameStringResource == SettingsR.string.ce_pref_cast_enabled }
    }

    @Test
    fun `hidden when CastBuild is None on phone`() {
        assertFalse(shown(AppVariant.PHONE, CastBuild.None))
    }

    @Test
    fun `shown when CastBuild is included on phone`() {
        assertTrue(shown(AppVariant.PHONE, CastBuild { true }))
    }

    @Test
    fun `hidden on TV even if CastBuild is true`() {
        assertFalse(shown(AppVariant.TV, CastBuild { true }))
    }
}
