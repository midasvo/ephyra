package dev.jdtech.jellyfin.presentation.film.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR

/** Tags the person icon that stands in for a missing photo, for tests. */
internal const val PersonPhotoPlaceholderTag = "person_photo_placeholder"

/**
 * CE (#396): a person's photo, or the person icon when there is none or it fails to load, as on
 * TV. While the photo loads the frame stays plain, so a photo never follows a flash of the icon.
 * [modifier] sizes and clips the frame.
 */
@Composable
fun PersonPhoto(
    uri: Uri?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 48.dp,
) {
    var failed by remember(uri) { mutableStateOf(false) }
    if (uri == null || failed) {
        Box(
            modifier =
                modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag(PersonPhotoPlaceholderTag)
                    .semantics {
                        if (contentDescription != null) this.contentDescription = contentDescription
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_user),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        AsyncImage(
            model = uri,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        )
    }
}
