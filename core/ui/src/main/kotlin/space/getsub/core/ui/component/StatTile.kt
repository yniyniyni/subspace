// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import space.getsub.core.ui.theme.RobotoMonoFontFamily

/**
 * A small value/label pair for a single metric — e.g. latency or data used
 * today.
 *
 * Built during the design system delivery and left unrendered until a milestone
 * had a real value for it, because this project treats a number that looks
 * measured but is invented as the exact failure mode ARCHITECTURE.md §10.1 warns
 * about — the design prototype's own "42 MS LATENCY" / "2.1 GB USED TODAY" are
 * placeholder text, not measurements.
 *
 * **M4.5 is the milestone that fed the latency slot**, from `:feature:home`.
 * Note what it did *not* do: an unmeasured server renders an em-dash through
 * this component, not a zero, because [value] is formatted by the caller and the
 * caller branches on the measurement's outcome first.
 *
 * The traffic slot is still unfed. M7 owns it, via the Xray stats API (§14.4),
 * and the same rule applies — render it when there is something real to render.
 *
 * @param value the metric, already formatted by the caller (e.g. "42 ms").
 *   This component does not format, round, or unit-suffix its input — that
 *   is the caller's job, once a caller has a real measurement to format. Set
 *   in [RobotoMonoFontFamily], `Type.kt`'s convention for every
 *   machine-generated value in this design system (addresses, ports,
 *   quotas), which is what a real latency/quota reading would also be.
 * @param label what [value] measures (e.g. "Latency").
 * @param accent whether this tile should draw attention, rendered with
 *   [MaterialTheme]'s `primary` role rather than
 *   [space.getsub.core.ui.theme.LocalSubspaceColors]'s `pop` —
 *   `colors.css` reserves `pop` for exactly one call-to-action per screen
 *   (see the `SubspaceColors` KDoc in `Color.kt`), and a stat tile is never
 *   that CTA.
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    accent: Boolean,
    modifier: Modifier = Modifier,
) {
    val valueColor = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = RobotoMonoFontFamily,
            color = valueColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
