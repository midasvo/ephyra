package dev.jdtech.jellyfin.presentation.settings

import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import org.junit.Assert.assertEquals
import org.junit.Test

/** #384: the licence dialog shows the bundled text, else the licence URL, never an empty body. */
class LicenseDialogTextTest {
    private fun library(vararg licenses: License) =
        Library(
            uniqueId = "group:artifact",
            artifactVersion = "1.0",
            name = "Artifact",
            description = null,
            website = null,
            developers = emptyList(),
            organization = null,
            scm = null,
            licenses = licenses.toSet(),
        )

    private fun license(hash: String, url: String?, content: String?) =
        License(name = hash, url = url, licenseContent = content, hash = hash)

    @Test
    fun `bundled text wins over the url`() {
        val lib = library(license("Apache-2.0", "https://spdx.org/licenses/Apache-2.0.html", "Apache text"))
        assertEquals(LicenseDialogText.Content("Apache text"), licenseDialogText(lib))
    }

    @Test
    fun `several bundled texts are joined`() {
        val lib = library(license("MIT", null, "MIT text"), license("GPL", null, "GPL text"))
        assertEquals(LicenseDialogText.Content("MIT text\n\nGPL text"), licenseDialogText(lib))
    }

    @Test
    fun `a licence without text shows its url`() {
        val lib = library(license("LGPL", "https://www.gnu.org/licenses/lgpl-3.0.txt", null))
        assertEquals(
            LicenseDialogText.Links(listOf("https://www.gnu.org/licenses/lgpl-3.0.txt")),
            licenseDialogText(lib),
        )
    }

    @Test
    fun `blank text counts as no text`() {
        val lib = library(license("LGPL", "https://example.org/lgpl", "  "))
        assertEquals(LicenseDialogText.Links(listOf("https://example.org/lgpl")), licenseDialogText(lib))
    }

    @Test
    fun `no text and no url shows the fallback`() {
        assertEquals(LicenseDialogText.None, licenseDialogText(library(license("X", null, null))))
        assertEquals(LicenseDialogText.None, licenseDialogText(library()))
    }
}
