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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.TagTraffic
import space.getsub.core.ui.format.formatByteCount

/**
 * The outbound tag a `metrics` block itself registers
 * (`core/xray/.../MetricsBlocks.kt`'s `METRICS_RESERVED_TAG`). Duplicated as
 * a literal rather than imported: `:feature:home` does not depend on
 * `:core:xray` (ARCHITECTURE.md §4 draws no line against it, but nothing else
 * here needs that module either, and one string constant is not the reason to
 * add it) — the same "kept in step by hand" tradeoff §4 already accepts for
 * `StoredProfile.connectable` versus `XrayConfigGenerator`'s own transport set.
 *
 * Filtered out of [BreakdownSection]'s rows below: xray registers this
 * outbound whenever the breakdown is on, whether or not the user's config
 * names anything called `Metrics`, so without this filter the section would
 * list app plumbing — `Metrics — 0 B` — as one of the user's own servers.
 */
private const val METRICS_ROW_TAG = "Metrics"

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
 * in-session disconnect (see that property's KDoc). [METRICS_ROW_TAG] is
 * filtered out of every row list this function looks at, including for the
 * latch below — the app's own housekeeping outbound must never count as "the
 * breakdown has real data".
 *
 * With the setting on, three renderings remain, and they must stay visibly
 * distinct:
 * - rows present → the real breakdown.
 * - rows empty, never latched (see below), and the active row is a `RAW_JSON`
 *   profile ([space.getsub.core.data.StoredProfile.runsAsWritten]) → the
 *   header alone, plus [R.string.home_breakdown_unavailable_passthrough]
 *   saying the breakdown isn't available for this connection *right now*
 *   (review M-C: a working override-branch session shows it too until its
 *   first rows arrive, which the latch below cannot prevent), rather than a
 *   blank space where rows would otherwise be. It deliberately names **no
 *   reason** (ruling R51, device finding N4): this screen cannot see which
 *   branch the session ran. The pure passthrough branch cannot carry the
 *   breakdown, but neither can an override-branch session whose breakdown
 *   was dropped by the `Metrics` tag-collision degrade — and for that one the
 *   old "config runs unmodified" reason was false. Naming the real reason
 *   needs a per-session signal from the service, deferred to Part 3 (R43).
 * - rows empty otherwise (including latched) → the header alone, with neither
 *   rows nor an explanation — "on, but nothing has moved yet" is a real,
 *   different state from the one above, and inventing a zeroed row for it
 *   would be exactly the fabricated-number failure this section exists to
 *   avoid.
 *
 * **Latched, not just checked live.** `runsAsWritten` names a *profile*, while
 * ARCHITECTURE.md §6's pure-vs-override split is decided per *connect
 * attempt*, from routing/DNS state this screen does not see — a `RAW_JSON`
 * profile connected with an active rule set or a custom DNS resolver runs the
 * override branch, which *does* carry the breakdown. Without latching, such a
 * session would show the "unavailable" copy on the first tick (a false
 * statement about a config that in fact carries the breakdown), and then flip
 * back to it every time a single poll happens to come back empty for the rest
 * of the session — `fetchMetricsPayload` returns `null` on any transient
 * failure, and `TrafficSample.perTag` has no memory of its own. `everSeenRows`
 * remembers, **per connection** (keyed on [ConnectionState.Connected]'s own
 * `sinceEpochMillis`, so a new session starts unlatched), that real rows have
 * arrived at least once, and once true this function never falls back to the
 * "unavailable" branch again for that connection — a momentary empty tick
 * after that renders the header alone, same as "nothing yet", which is the
 * honest reading of a session already known to carry the breakdown.
 */
@Composable
internal fun BreakdownSection(
    state: HomeState,
    modifier: Modifier = Modifier,
) {
    val connection = state.connection
    if (!state.perTagBreakdownEnabled || connection !is ConnectionState.Connected) return

    val rows = state.traffic?.perTag.orEmpty().filterNot { it.tag == METRICS_ROW_TAG }
    var everSeenRows by remember(connection.sinceEpochMillis) { mutableStateOf(false) }
    if (rows.isNotEmpty()) everSeenRows = true

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
            everSeenRows -> Unit // known to work; this tick just has nothing.
            // Reason-free on purpose (R51): runsAsWritten is not "the pure
            // branch ran" — see the KDoc. The specific reason waits on Part 3's
            // per-session signal (R43).
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
