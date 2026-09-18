// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.TagTraffic
import space.getsub.core.ui.format.formatByteCount

/**
 * The per-outbound-tag rows beneath [StatTilesRow] (M8.5 spec §2), decomposing
 * the totals that row already shows. Its own file, not part of `HomeScreen.kt`:
 * that file is already at the `TooManyFunctions` budget, and this section has
 * no other reason to live there.
 *
 * Nothing renders at all — not the header either — unless
 * [HomeState.perTagBreakdownEnabled] is on: the setting mirrors
 * `SettingsRepository.perTagBreakdown` verbatim rather than being inferred from
 * [HomeState.traffic]`.perTag`, because that list is empty for three different
 * reasons ([space.getsub.core.model.TrafficSample.perTag]'s own KDoc) and only
 * this flag tells "off" apart from the other two — rendering all three the same
 * way would be `ARCHITECTURE.md` §10.1's signature failure.
 *
 * Gated on [ConnectionState.Connected] like [StatTilesRow]'s own DOWN/UP tiles,
 * for the same reason: [HomeState.traffic] is a cache that can outlive an
 * in-session disconnect (see that property's KDoc).
 *
 * With the setting on, three renderings remain, and they must stay visibly
 * distinct:
 * - rows present → the real breakdown.
 * - rows empty and the active row is a `RAW_JSON` profile
 *   ([space.getsub.core.data.StoredProfile.runsAsWritten]) → the header alone,
 *   plus [R.string.home_breakdown_unavailable_passthrough] naming why, rather
 *   than a blank space where rows would otherwise be.
 * - rows empty otherwise → the header alone, with neither rows nor an
 *   explanation — "on, but nothing has moved yet" is a real, different state
 *   from the one above, and inventing a zeroed row for it would be exactly the
 *   fabricated-number failure this section exists to avoid.
 *
 * `runsAsWritten` is what this screen has, not a perfect signal: it names a
 * *profile*, while ARCHITECTURE.md §6's pure-vs-override split is decided per
 * *connect attempt*, from routing/DNS state this screen does not see. A
 * `RAW_JSON` profile connected with an active rule set or a custom DNS resolver
 * runs the override branch, which *does* carry the breakdown — for that session
 * this shows the "unavailable" copy until the first rows arrive, after which
 * the first branch above takes over. Closing that gap needs a session-level
 * signal from the service; nothing in this milestone threads one to Home.
 */
@Composable
internal fun BreakdownSection(
    state: HomeState,
    modifier: Modifier = Modifier,
) {
    if (!state.perTagBreakdownEnabled || state.connection !is ConnectionState.Connected) return

    val rows = state.traffic?.perTag.orEmpty()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.home_breakdown_header),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            rows.isNotEmpty() -> rows.forEach { row -> BreakdownRow(row) }
            state.activeProfile?.runsAsWritten == true ->
                Text(
                    text = stringResource(R.string.home_breakdown_unavailable_passthrough),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            // else: on, eligible, nothing has moved yet — the header renders
            // alone, deliberately with neither rows nor an explanation.
        }
    }
}

@Composable
private fun BreakdownRow(
    row: TagTraffic,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = row.tag, style = MaterialTheme.typography.bodyMedium)
        Text(
            text =
            stringResource(
                R.string.home_breakdown_row_traffic,
                formatByteCount(row.downlinkBytes),
                formatByteCount(row.uplinkBytes),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
