package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyPerson
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidItemPersonImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #389: a cast card opens the person wherever it is tapped. The name and role under the photo used
 * to take the tap themselves and do nothing. #396: a card without a photo shows the person icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonItemTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var clicks = 0

    @Test
    fun `tapping the photo opens the person`() {
        show()

        rule
            .onNodeWithContentDescription(
                rule.activity.getString(CoreR.string.person_image_description, dummyPerson.name),
                useUnmergedTree = true,
            )
            .performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `tapping the name opens the person`() {
        show()

        rule.onNodeWithText(dummyPerson.name, useUnmergedTree = true).performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `tapping the role opens the person`() {
        show()

        rule.onNodeWithText(dummyPerson.role, useUnmergedTree = true).performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `the card is one click target`() {
        show()

        rule.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `a disabled card opens nothing`() {
        show(enabled = false)

        rule.onNodeWithText(dummyPerson.name, useUnmergedTree = true).performClick()
        rule.onNodeWithText(dummyPerson.role, useUnmergedTree = true).performClick()

        assertEquals(0, clicks)
    }

    @Test
    fun `a person without a photo gets the person icon`() {
        show(person = withPhoto(null))

        rule.onNodeWithTag(PersonPhotoPlaceholderTag, useUnmergedTree = true).assertExists()
        rule
            .onNodeWithContentDescription(
                rule.activity.getString(CoreR.string.person_image_description, dummyPerson.name),
                useUnmergedTree = true,
            )
            .assertExists()
    }

    @Test
    fun `a photo that fails to load gives way to the person icon`() {
        show(person = withPhoto(Uri.fromFile(File("/does/not/exist.jpg"))))

        rule.waitUntil(timeoutMillis = 5_000) {
            rule
                .onAllNodesWithTag(PersonPhotoPlaceholderTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun withPhoto(uri: Uri?) =
        dummyPerson.copy(image = FindroidItemPersonImage(uri = uri, blurHash = null))

    private fun show(person: FindroidItemPerson = dummyPerson, enabled: Boolean = true) {
        rule.setContent { PersonItem(person = person, onClick = { clicks++ }, enabled = enabled) }
    }
}
