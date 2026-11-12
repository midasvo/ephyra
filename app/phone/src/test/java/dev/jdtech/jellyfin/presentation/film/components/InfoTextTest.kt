package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidItemPersonImage
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import org.jellyfin.sdk.model.api.PersonKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #510: the genres line and the credits table under an item's overview. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS")
class InfoTextTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun person(name: String) =
        FindroidItemPerson(
            id = UUID.randomUUID(),
            name = name,
            type = PersonKind.DIRECTOR,
            role = "",
            image = FindroidItemPersonImage(null, null),
        )

    @Test
    fun genresAreOneLineWithoutALabelThatAScreenReaderStillNames() {
        rule.setContent {
            FindroidTheme {
                InfoText(
                    genres = listOf("Animation", "Comedy", "Family"),
                    director = null,
                    writers = emptyList(),
                )
            }
        }

        rule
            .onNodeWithTag(INFO_GENRES_TAG)
            .assertTextEquals("Animation · Comedy · Family")
            .assertContentDescriptionEquals("Genres: Animation, Comedy, Family")
        rule.onAllNodesWithTag(INFO_CREDIT_TAG).assertCountEquals(0)
    }

    @Test
    fun theCreditsAreATableWhoseNamesStartOnOneEdge() {
        rule.setContent {
            FindroidTheme {
                InfoText(
                    genres = emptyList(),
                    director = person("Sacha Goedegebure"),
                    writers = listOf(person("Sacha Goedegebure"), person("Ton Roosendaal")),
                    studios = listOf("Blender Foundation"),
                )
            }
        }

        rule.onAllNodesWithTag(INFO_CREDIT_TAG).assertCountEquals(3)
        rule.onAllNodesWithTag(INFO_GENRES_TAG).assertCountEquals(0)
        rule.onNodeWithText("Director", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Writers", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Studios", useUnmergedTree = true).assertIsDisplayed()

        val director = rule.onNodeWithText("Sacha Goedegebure", useUnmergedTree = true)
        val left = director.getUnclippedBoundsInRoot().left
        rule
            .onNodeWithText("Sacha Goedegebure, Ton Roosendaal", useUnmergedTree = true)
            .assertLeftPositionInRootIsEqualTo(left)
        rule
            .onNodeWithText("Blender Foundation", useUnmergedTree = true)
            .assertLeftPositionInRootIsEqualTo(left)
    }

    @Test
    fun aShowNamesItsNetworks() {
        rule.setContent {
            FindroidTheme {
                InfoText(
                    genres = emptyList(),
                    director = null,
                    writers = emptyList(),
                    studios = listOf("AMC"),
                    studiosAreNetworks = true,
                )
            }
        }

        rule.onNodeWithText("Networks", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("AMC", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun nothingToShowTakesNoSpace() {
        rule.setContent {
            FindroidTheme {
                InfoText(genres = emptyList(), director = null, writers = emptyList())
            }
        }

        rule.onAllNodesWithTag(INFO_GENRES_TAG).assertCountEquals(0)
        rule.onAllNodesWithTag(INFO_CREDIT_TAG).assertCountEquals(0)
    }
}
