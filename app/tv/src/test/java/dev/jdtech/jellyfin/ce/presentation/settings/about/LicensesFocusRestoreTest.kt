package dev.jdtech.jellyfin.ce.presentation.settings.about

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.util.withJson
import dev.jdtech.jellyfin.R
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Open → Back → same entry focused" for the TV licences list (#424, ADR 0003). A
 * SaveableStateHolder swaps the list out and back in, the way Navigation 3 does; the libraries load
 * again on the way back, as they do in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp")
class LicensesFocusRestoreTest {
    @get:Rule val rule = createComposeRule()

    private val libraries: List<Library> =
        Libs.Builder()
            .withJson(ApplicationProvider.getApplicationContext(), R.raw.aboutlibraries)
            .build()
            .libraries
            .sortedBy { it.name.lowercase() }

    private var licenseOpen by mutableStateOf(false)
    private var loaded by mutableStateOf(true)
    private var opened: String? = null

    private fun setUp() {
        rule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            FindroidTheme {
                if (licenseOpen) {
                    stateHolder.SaveableStateProvider("license") { Box {} }
                } else {
                    stateHolder.SaveableStateProvider("licenses") {
                        LicensesLayout(
                            libraries = if (loaded) libraries else null,
                            onLibraryClick = {
                                opened = it
                                licenseOpen = true
                            },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun row(index: Int) = rule.onNodeWithTag(licenseRowTag(libraries[index].uniqueId))

    @Test
    fun firstVisitFocusesTheFirstEntry() {
        setUp()
        row(0).assertIsFocused()
    }

    @Test
    fun backFocusesTheEntryThatWasOpened() {
        setUp()
        val target = 12

        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(target)
        row(target).requestFocus()
        row(target).assertIsFocused()

        // OK opens it: the list leaves composition.
        row(target).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(libraries[target].uniqueId, opened)

        // Back: the libraries load again (a spinner first), then the list comes back.
        loaded = false
        licenseOpen = false
        rule.waitForIdle()
        loaded = true
        rule.waitForIdle()

        row(target).assertIsFocused()
    }
}
