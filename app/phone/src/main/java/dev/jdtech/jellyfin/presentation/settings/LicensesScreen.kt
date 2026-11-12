package dev.jdtech.jellyfin.presentation.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryDetailMode
import dev.jdtech.jellyfin.R
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.utils.Layout
import dev.jdtech.jellyfin.presentation.utils.plus
import dev.jdtech.jellyfin.settings.R as SettingsR

/** CE (#211): the open-source library list, which used to fill most of the About screen. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LicensesScreen(navigateBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val libraries by produceLibraries(R.raw.aboutlibraries)

    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .recalculateWindowInsets()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(SettingsR.string.about_licenses)) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        // CE (#384): a tap opens the licence text in a dialog. The library's default (Inline) only
        // shows buttons, and its "View license" opens the licence's web page instead of the text.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // CE (#434): the settings cap (4-design.md §12) as side padding of the list, so the
            // margins still scroll it.
            val side = ((maxWidth - Layout.settingsMaxWidth) / 2).coerceAtLeast(0.dp)
            LibrariesContainer(
                libraries = libraries,
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(horizontal = side),
                detailMode = LibraryDetailMode.Dialog,
                licenseDialogBody = { library, modifier -> LicenseDialogBody(library, modifier) },
                licenseDialogConfirmText = stringResource(CoreR.string.close),
            )
        }
    }
}

@Composable
private fun LicenseDialogBody(library: Library, modifier: Modifier) {
    when (val text = licenseDialogText(library)) {
        is LicenseDialogText.Content ->
            SelectionContainer(modifier = modifier) { Text(text = text.text) }
        is LicenseDialogText.Links ->
            Text(
                text =
                    buildAnnotatedString {
                        text.urls.forEachIndexed { index, url ->
                            if (index > 0) append("\n\n")
                            withLink(
                                LinkAnnotation.Url(
                                    url,
                                    TextLinkStyles(
                                        SpanStyle(textDecoration = TextDecoration.Underline)
                                    ),
                                )
                            ) {
                                append(url)
                            }
                        }
                    },
                modifier = modifier,
            )
        LicenseDialogText.None ->
            Text(text = stringResource(SettingsR.string.about_no_license_text), modifier = modifier)
    }
}

/** What the licence dialog shows: the bundled licence texts, else the licences' URLs. */
internal sealed interface LicenseDialogText {
    data class Content(val text: String) : LicenseDialogText

    data class Links(val urls: List<String>) : LicenseDialogText

    data object None : LicenseDialogText
}

internal fun licenseDialogText(library: Library): LicenseDialogText {
    val contents = library.licenses.mapNotNull { it.licenseContent?.takeIf(String::isNotBlank) }
    if (contents.isNotEmpty()) return LicenseDialogText.Content(contents.joinToString("\n\n"))
    val urls = library.licenses.mapNotNull { it.url?.takeIf(String::isNotBlank) }
    return if (urls.isNotEmpty()) LicenseDialogText.Links(urls) else LicenseDialogText.None
}
