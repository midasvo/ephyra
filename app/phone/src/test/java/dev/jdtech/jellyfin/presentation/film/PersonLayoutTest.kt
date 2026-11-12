package dev.jdtech.jellyfin.presentation.film

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyPersonDetail
import dev.jdtech.jellyfin.film.presentation.person.PersonState
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #436: from medium width up the person page puts the photo beside the name, at the start
 * margin. It starts below the back and home buttons instead of under them, and the name and
 * biography column is never wider than 720 dp (docs/tablet/4-design.md rule 4, §8).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PersonLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // A biography long enough to run across a 1280 dp window.
    private val person =
        dummyPersonDetail.copy(overview = List(4) { dummyPersonDetail.overview }.joinToString(" "))

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `expanded page starts below the top bar, text at most 720 dp`() {
        show()

        // The name is at the top of the row, level with the photo.
        assertBelowTopBar()
        assertTrue("biography ${biography().width} wide", biography().width <= 720.dp)
    }

    @Test
    @Config(qualifiers = "w701dp-h841dp-port")
    fun `medium page starts below the top bar`() {
        show()

        assertBelowTopBar()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-port")
    fun `the compact page keeps the photo above the name`() {
        show()

        // The centred 320 dp photo sits between the buttons and the name, as before.
        assertTrue("name at ${name().top}", name().top >= 320.dp)
    }

    private fun assertBelowTopBar() {
        val name = name()
        val back = back()
        assertTrue("name at ${name.top}, buttons end at ${back.bottom}", name.top >= back.bottom)
    }

    private fun name(): DpRect = rule.onNodeWithText(person.name).getUnclippedBoundsInRoot()

    private fun biography(): DpRect =
        rule.onNodeWithText(person.overview).getUnclippedBoundsInRoot()

    private fun back(): DpRect =
        rule
            .onNodeWithContentDescription(rule.activity.getString(CoreR.string.navigate_back))
            .getUnclippedBoundsInRoot()

    private fun show() {
        rule.setContent {
            FindroidTheme {
                PersonScreenLayout(state = PersonState(person = person), onAction = {})
            }
        }
    }
}
