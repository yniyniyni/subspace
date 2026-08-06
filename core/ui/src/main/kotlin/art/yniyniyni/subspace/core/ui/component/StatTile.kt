// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import art.yniyniyni.subspace.core.ui.theme.RobotoMonoFontFamily

/**
 * A small value/label pair for a single metric — e.g. latency or data used
 * today.
 *
 * Built now because it belongs to the design system delivery and later
 * milestones (M4, M7) consume it, but **M3 renders it nowhere**: M3 measures
 * neither latency nor traffic volume, and this project treats a number that
 * looks measured but is invented as the exact failure mode ARCHITECTURE.md
 * §10.1 warns about — the design prototype's own "42 MS LATENCY" / "2.1 GB
 * USED TODAY" are placeholder text, not real numbers, and are not wired in
 * here for that reason. Whichever milestone first has a real value to show
 * should be the one that renders this.
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
 *   [art.yniyniyni.subspace.core.ui.theme.LocalSubspaceColors]'s `pop` —
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
