// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on every dp value below. All are design
// values transcribed from the design system's floating-chrome notes (26dp
// gesture-bar gap, 104dp content reservation, 8dp inner padding, 1dp border,
// elevation 3) or tokens/spacing.css's --space-* scale — already named by the
// val each initializes. See ConnectControl.kt and Motion.kt for the same
// pattern.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.core.ui.component

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import art.yniyniyni.subspace.core.ui.theme.ShapeCornerFull
import art.yniyniyni.subspace.core.ui.theme.motionSpringSpec

/**
 * `26dp` — the design system's floating-chrome note: the pill sits this far
 * above the gesture bar, not merely flush with the navigation-bar inset.
 */
private val NAV_PILL_GESTURE_BAR_GAP = 26.dp

/** The pill's own internal padding, per the floating-chrome note. */
private val NAV_PILL_INNER_PADDING = 8.dp

/** `--elevation-3`'s border half of the pair — 1dp `outlineVariant`. */
private val NAV_PILL_BORDER_WIDTH = 1.dp

/**
 * `--elevation-3` (`tokens/elevation.css`) is a CSS box-shadow, not a Compose
 * dp value. `elevation.css`'s six `--elevation-0..5` entries are the same
 * six-step scale Material 3 itself ships as `ElevationTokens.Level0..5` (0,
 * 1, 3, 6, 8, 12dp) — decompiling `material3-android` confirms
 * `BottomSheetDefaults`'s own elevation tokens resolve through exactly that
 * enum. Level 3 of that scale, 6dp, is the standard Android reading of
 * `--elevation-3` and is used for both [Surface]'s `shadowElevation` (the
 * cast shadow the box-shadow token describes) and `tonalElevation` (Material
 * 3's own surface-tinting response to elevation, which the CSS token has no
 * equivalent for but which every other Surface in this design system gets by
 * default).
 */
private val NAV_PILL_ELEVATION = 6.dp

/** `--space-2` (`tokens/spacing.css`, 8px): gap between destinations in the pill. */
private val NAV_ITEM_GAP = 8.dp

/** `--space-3` (`tokens/spacing.css`, 12px): a destination's own horizontal padding. */
private val NAV_ITEM_HORIZONTAL_PADDING = 12.dp

/** `--space-2` (`tokens/spacing.css`, 8px): a destination's own vertical padding. */
private val NAV_ITEM_VERTICAL_PADDING = 8.dp

/** `--space-2` (`tokens/spacing.css`, 8px): gap between a destination's icon and its label. */
private val NAV_ITEM_ICON_LABEL_GAP = 8.dp

/**
 * The vertical space [FloatingNavigationBar] needs behind it.
 *
 * Every screen that scrolls content beneath the floating nav pill must add
 * this as bottom content padding so the pill never covers the last item —
 * the design system's own note: "content beneath reserves ~104dp of bottom
 * padding". A single public constant so this number lives in one place, not
 * re-guessed (and inevitably drifted) per screen.
 */
val FLOATING_NAV_CONTENT_BOTTOM_PADDING = 104.dp

/**
 * One destination in [FloatingNavigationBar].
 *
 * @param value the caller's own identifier for this destination (typically a
 *   route name), compared against [FloatingNavigationBar]'s `selected` to
 *   decide which item is expanded and reported back through `onSelect` when
 *   this destination is tapped.
 * @param labelRes the destination's name. Rendered as visible text only for
 *   the selected destination (unselected ones show icon-only, per the design
 *   system), but used as every destination's [contentDescription]
 *   regardless of selection — see [FloatingNavigationBar]'s KDoc.
 * @param icon the destination's icon, always visible.
 */
data class NavItem(
    val value: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

/**
 * The app's floating navigation chrome: a pill of destinations that floats
 * [NAV_PILL_GESTURE_BAR_GAP] above the system gesture bar rather than
 * docking to the screen edge, on `surfaceContainerHigh` with a 1dp
 * `outlineVariant` border, `--elevation-3` and `--shape-corner-full` — the
 * design system's own notes on this chrome.
 *
 * Only the selected destination shows its label; the rest are icon-only, and
 * the active destination expands into icon+label with [motionSpringSpec] (the
 * design system's "spring" motion token) rather than snapping. This means an
 * unselected destination's [Text] label does not exist in the composition at
 * all — not merely at zero size — so it carries no accessible name of its
 * own. [NavItem.labelRes] is applied as an explicit [contentDescription] on
 * those destinations instead, which is why every destination — selected or
 * not — remains reachable by the same name a sighted user sees.
 *
 * Positioning beyond the 26dp gesture-bar gap (screen placement, z-order over
 * other content) is the caller's responsibility, same as every other
 * component in this module.
 *
 * @param items the destinations to render, in order.
 * @param selected the [NavItem.value] of the currently active destination.
 * @param onSelect invoked with a destination's [NavItem.value] when it is
 *   tapped, including re-taps of the already-selected destination — this
 *   component does not suppress that; a caller that wants "tap current tab to
 *   scroll to top" or similar needs to see every tap.
 */
@Composable
fun FloatingNavigationBar(
    items: List<NavItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = NAV_PILL_GESTURE_BAR_GAP),
        shape = ShapeCornerFull,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(NAV_PILL_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = NAV_PILL_ELEVATION,
        tonalElevation = NAV_PILL_ELEVATION,
    ) {
        Row(
            modifier = Modifier.padding(NAV_PILL_INNER_PADDING),
            horizontalArrangement = Arrangement.spacedBy(NAV_ITEM_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                NavDestination(
                    item = item,
                    isSelected = item.value == selected,
                    onClick = { onSelect(item.value) },
                )
            }
        }
    }
}

/**
 * One entry inside [FloatingNavigationBar]'s pill. Private: the pill is the
 * public unit this design system exposes, the same way [ConnectControl]
 * exposes no public seam for its own internal halo.
 */
@Composable
private fun NavDestination(
    item: NavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(item.labelRes)
    // Material 3's own NavigationBarItem pairing for a selected destination's
    // indicator pill — colors.css defines no nav-specific token of its own,
    // so this is Material's default nav-selection role, not a colors.css
    // alias the way surfaceContainerHigh/outlineVariant above are.
    val contentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        modifier =
        modifier
            .clip(ShapeCornerFull)
            .let { if (isSelected) it.background(MaterialTheme.colorScheme.secondaryContainer) else it }
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
            .let { if (!isSelected) it.semantics { contentDescription = label } else it }
            .padding(horizontal = NAV_ITEM_HORIZONTAL_PADDING, vertical = NAV_ITEM_VERTICAL_PADDING),
        horizontalArrangement = Arrangement.spacedBy(NAV_ITEM_ICON_LABEL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = item.icon,
            // Decorative: the Row's own selectable() semantics (merged with
            // the explicit contentDescription above for unselected items, or
            // with the visible Text below for the selected one) already
            // names this destination once — a second name on the icon would
            // duplicate it.
            contentDescription = null,
            tint = contentColor,
        )
        AnimatedVisibility(
            visible = isSelected,
            enter = fadeIn() + expandHorizontally(animationSpec = motionSpringSpec()),
            exit = fadeOut() + shrinkHorizontally(animationSpec = motionSpringSpec()),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = contentColor)
        }
    }
}
