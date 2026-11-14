package dev.jdtech.jellyfin.models

import android.net.Uri
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType

data class FindroidImages(
    val primary: Uri? = null,
    val backdrop: Uri? = null,
    val logo: Uri? = null,
    val showPrimary: Uri? = null,
    val showBackdrop: Uri? = null,
    val showLogo: Uri? = null,
    // CE (#608): the wide artwork with the title on it. Only the Continue Watching and Next Up
    // rows ask the server for it, so it is null everywhere else.
    val thumb: Uri? = null,
    val showThumb: Uri? = null,
)

fun BaseItemDto.toFindroidImages(jellyfinRepository: JellyfinRepository): FindroidImages {
    val baseUrl = Uri.parse(jellyfinRepository.getBaseUrl())
    val primary =
        imageTags?.get(ImageType.PRIMARY)?.let { tag ->
            baseUrl
                .buildUpon()
                .appendEncodedPath("items/$id/Images/${ImageType.PRIMARY}")
                .appendQueryParameter("tag", tag)
                .build()
        }
    val backdrop =
        backdropImageTags?.firstOrNull()?.let { tag ->
            baseUrl
                .buildUpon()
                .appendEncodedPath("items/$id/Images/${ImageType.BACKDROP}/0")
                .appendQueryParameter("tag", tag)
                .build()
        }
    val logo =
        imageTags?.get(ImageType.LOGO)?.let { tag ->
            baseUrl
                .buildUpon()
                .appendEncodedPath("items/$id/Images/${ImageType.LOGO}")
                .appendQueryParameter("tag", tag)
                .build()
        }
    val showPrimary = seriesPrimaryImageTag?.let { tag ->
        baseUrl
            .buildUpon()
            .appendEncodedPath("items/$seriesId/Images/${ImageType.PRIMARY}")
            .appendQueryParameter("tag", tag)
            .build()
    }
    // The series' own backdrop and logo. The parent item id names the owner of the image (the
    // series unless the server inherited it from further up). No tag means the series has none,
    // so these stay null and callers fall back to the episode or season image.
    val showBackdrop =
        parentBackdropImageTags?.firstOrNull()?.let { tag ->
            baseUrl
                .buildUpon()
                .appendEncodedPath(
                    "items/${parentBackdropItemId ?: seriesId}/Images/${ImageType.BACKDROP}/0"
                )
                .appendQueryParameter("tag", tag)
                .build()
        }
    val showLogo = parentLogoImageTag?.let { tag ->
        baseUrl
            .buildUpon()
            .appendEncodedPath("items/${parentLogoItemId ?: seriesId}/Images/${ImageType.LOGO}")
            .appendQueryParameter("tag", tag)
            .build()
    }
    val thumb =
        imageTags?.get(ImageType.THUMB)?.let { tag ->
            baseUrl
                .buildUpon()
                .appendEncodedPath("items/$id/Images/${ImageType.THUMB}")
                .appendQueryParameter("tag", tag)
                .build()
        }
    // The series' thumb. The server names it for an episode; for anything else below a series it
    // is the parent's, which is only sent when the item has no thumb of its own.
    val showThumb =
        seriesThumbImageTag
            ?.takeIf { seriesId != null }
            ?.let { tag ->
                baseUrl
                    .buildUpon()
                    .appendEncodedPath("items/$seriesId/Images/${ImageType.THUMB}")
                    .appendQueryParameter("tag", tag)
                    .build()
            }
            ?: parentThumbImageTag?.let { tag ->
                baseUrl
                    .buildUpon()
                    .appendEncodedPath(
                        "items/${parentThumbItemId ?: seriesId}/Images/${ImageType.THUMB}"
                    )
                    .appendQueryParameter("tag", tag)
                    .build()
            }

    return FindroidImages(
        primary = primary,
        backdrop = backdrop,
        logo = logo,
        showPrimary = showPrimary,
        showBackdrop = showBackdrop,
        showLogo = showLogo,
        thumb = thumb,
        showThumb = showThumb,
    )
}

fun FindroidMovieDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = Uri.Builder().appendEncodedPath("images/$itemId/primary").build(),
        backdrop = Uri.Builder().appendEncodedPath("images/$itemId/backdrop").build(),
    )
}

fun FindroidShowDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = Uri.Builder().appendEncodedPath("images/$itemId/primary").build(),
        backdrop = Uri.Builder().appendEncodedPath("images/$itemId/backdrop").build(),
    )
}

fun FindroidSeasonDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = Uri.Builder().appendEncodedPath("images/$itemId/primary").build(),
        backdrop = Uri.Builder().appendEncodedPath("images/$itemId/backdrop").build(),
        showPrimary = Uri.Builder().appendEncodedPath("images/$seriesId/primary").build(),
        showBackdrop = Uri.Builder().appendEncodedPath("images/$seriesId/backdrop").build(),
    )
}

fun FindroidEpisodeDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = Uri.Builder().appendEncodedPath("images/$itemId/primary").build(),
        backdrop = Uri.Builder().appendEncodedPath("images/$itemId/backdrop").build(),
        showPrimary = Uri.Builder().appendEncodedPath("images/$seriesId/primary").build(),
        showBackdrop = Uri.Builder().appendEncodedPath("images/$seriesId/backdrop").build(),
    )
}
