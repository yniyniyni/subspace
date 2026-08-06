// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the title's padding — tokens/spacing.css
// values (--space-2, --space-4), already named by the val each initializes.
// See ConnectControl.kt for the same pattern.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.core.ui.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** `--space-4` (`tokens/spacing.css`, 16px): the title's horizontal inset. */
private val SHEET_TITLE_HORIZONTAL_PADDING = 16.dp

/** `--space-2` (`tokens/spacing.css`, 8px): gap between the drag handle and the title. */
private val SHEET_TITLE_TOP_PADDING = 8.dp

/** `--space-4` (`tokens/spacing.css`, 16px): gap between the title and the caller's content. */
private val SHEET_TITLE_BOTTOM_PADDING = 16.dp

/**
 * The shared bottom sheet every screen presents its actions in.
 *
 * Wraps Material 3's [ModalBottomSheet] rather than replacing it, deliberately
 * leaving two things at Material's own default rather than re-declaring them:
 *
 * - **The drag handle** — [BottomSheetDefaults.DragHandle] already ships its
 *   own accessible names (`material3-android`'s own string resources:
 *   "Drag handle", "Expand/Collapse/Dismiss bottom sheet"), so nothing here
 *   needs to invent a second set.
 * - **The shape.** Decompiling `material3-android:1.4.0`
 *   (`SheetBottomTokens.DockedContainerShape` = `CornerExtraLargeTop`, which
 *   `ShapesKt.fromToken` resolves to `MaterialTheme.shapes.extraLarge.top(
 *   CornerNone)`) shows Material 3's own default sheet shape is *derived*
 *   from the ambient theme's `extraLarge` shape, top corners only.
 *   [art.yniyniyni.subspace.core.ui.theme.SubspaceShapes] already sets
 *   `extraLarge` to `--shape-corner-xl` (28dp) — so leaving `shape`
 *   unspecified here already *is* "the shape the design specifies", not a
 *   gap filled by an unrelated Material default.
 *
 * `containerColor`, `tonalElevation` and the scrim are likewise left at
 * Material's defaults: `colors.css` defines no sheet-specific token for any
 * of them, so there is nothing in the design system for a hand-picked value
 * here to be faithful *to*.
 *
 * @param open whether the sheet is shown. `false` composes nothing — the
 *   sheet (and the [rememberModalBottomSheetState] it would otherwise hold)
 *   does not exist in the composition tree while closed, the same convention
 *   [ConnectControl]'s halo uses for state this design system does not pay
 *   composition cost for when it isn't active.
 * @param titleRes the sheet's heading. Marked `Modifier.semantics { heading()
 *   }` so a screen-reader user can navigate directly to it rather than
 *   swiping through the sheet's contents to find it.
 * @param onDismiss invoked when the sheet is dismissed — scrim tap, swipe
 *   down, or the drag handle's own dismiss action.
 * @param content the sheet's body, below the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubspaceBottomSheet(
    open: Boolean,
    @StringRes titleRes: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!open) return

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge,
            modifier =
            Modifier
                .padding(
                    start = SHEET_TITLE_HORIZONTAL_PADDING,
                    end = SHEET_TITLE_HORIZONTAL_PADDING,
                    top = SHEET_TITLE_TOP_PADDING,
                    bottom = SHEET_TITLE_BOTTOM_PADDING,
                ).semantics { heading() },
        )
        content()
    }
}
