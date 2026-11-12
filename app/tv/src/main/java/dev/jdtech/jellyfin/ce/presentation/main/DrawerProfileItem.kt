package dev.jdtech.jellyfin.ce.presentation.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.ce.ui.focus.ephyraFocusedBorder

/**
 * #567: the drawer's profile item, with the user name over the server name. tv-material's
 * `NavigationDrawerItem` fixes its height at 48 dp (56 dp with supporting content), too low for
 * `titleMedium` over `bodySmall`, so this item draws the same pill at [DrawerProfileItemHeight]:
 * the same shape, colours, ring and width animation, with the leading content and the labels where
 * tv-material puts them (ADR 0002, safe area).
 */
@Composable
internal fun NavigationDrawerScope.DrawerProfileItem(
    userName: String,
    serverName: String?,
    onClick: () -> Unit,
    leadingContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val width by
        animateDpAsState(
            if (hasFocus) NavigationDrawerItemDefaults.ExpandedDrawerItemWidth
            else NavigationDrawerItemDefaults.CollapsedDrawerItemWidth,
            label = "drawer_profile_width",
        )
    val colors = NavigationDrawerItemDefaults.colors()
    val shape = NavigationDrawerItemDefaults.shape()
    Surface(
        onClick = onClick,
        shape =
            ClickableSurfaceDefaults.shape(
                shape = shape.shape,
                focusedShape = shape.focusedShape,
                pressedShape = shape.pressedShape,
            ),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = colors.containerColor,
                contentColor = if (hasFocus) colors.contentColor else colors.inactiveContentColor,
                focusedContainerColor = colors.focusedContainerColor,
                focusedContentColor = colors.focusedContentColor,
                pressedContainerColor = colors.pressedContainerColor,
                pressedContentColor = colors.pressedContentColor,
            ),
        // The other items don't grow on focus (seen on the device): neither does this one.
        scale = ClickableSurfaceScale.None,
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder = ephyraFocusedBorder(shape.shape, inside = true)
            ),
        interactionSource = interactionSource,
        modifier = modifier.width(width).height(DrawerProfileItemHeight),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxHeight()
                    .padding(
                        start = if (hasFocus) LeadingStartExpanded else LeadingStartCollapsed
                    ),
        ) {
            // The leading content's 24 dp box, as in the other items.
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
                leadingContent()
            }
            AnimatedVisibility(
                visible = hasFocus,
                enter = NavigationDrawerItemDefaults.ContentAnimationEnter,
                exit = NavigationDrawerItemDefaults.ContentAnimationExit,
            ) {
                Row {
                    Spacer(modifier = Modifier.width(LabelGap))
                    Column(modifier = Modifier.padding(end = 16.dp)) {
                        Text(
                            text = userName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("drawer_profile_name"),
                        )
                        if (serverName != null) {
                            Text(
                                text = serverName,
                                style = MaterialTheme.typography.bodySmall,
                                // The pill's own colour on the focused pill, where
                                // onSurfaceVariant would be faint.
                                color =
                                    if (focused) LocalContentColor.current
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("drawer_profile_server"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Two lines (`titleMedium` 22.5 sp over `bodySmall` 21 sp) and 10 dp above and below, like the 48
 * dp items. tv-material's own two-line list item is 64 dp high too.
 */
internal val DrawerProfileItemHeight = 64.dp

/** Where tv-material puts an item's 24 dp leading content: 16 dp in, 20 dp while expanded. */
private val LeadingStartCollapsed = 16.dp
private val LeadingStartExpanded = 20.dp

/** From the leading content to the labels, so they start where the other items' labels do. */
private val LabelGap = 20.dp
