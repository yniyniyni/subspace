// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import space.getsub.core.model.DnsState
import space.getsub.core.model.RoutingSourceKind
import space.getsub.core.model.RuleSetAssetFailure
import space.getsub.core.model.RuleSetAssetState

/**
 * What the rule set list screen shows: one row per stored rule set.
 *
 * @param activeRuleSetId carried rather than re-derived from `ruleSets`. Asking
 *   `ruleSets.none { it.isActive }` reports "off" for a first frame that has no
 *   rows yet, and for a stored id whose row has since been deleted — in both
 *   cases the UI would claim routing is off while the setting still holds a
 *   value. Null genuinely means off.
 */
internal data class RoutingState(
    val ruleSets: List<RuleSetRow> = emptyList(),
    val activeRuleSetId: Long? = null,
) {
    /**
     * Whether the explicit **Off** row is offered.
     *
     * Always, and unconditionally — `/off` and the `routing-enable` directive
     * both need a target the user can see, and "off" is a state the list can
     * always reach. Modelled as a property rather than assumed by the screen so
     * the rule has one home if it ever gains a condition.
     */
    val canTurnRoutingOff: Boolean get() = true
}

/**
 * A generation's live byte count.
 *
 * @param totalBytes null when the server declared no `Content-Length` — the row
 *   renders an indeterminate bar rather than `12 MB / 0 MB`.
 */
internal data class GeoProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
)

/**
 * One rule set's row.
 *
 * §5.6: nothing here carries an entry, a site or an IP — only a name (which the user chose to
 * label the set, not a value it routes) and a count. [subscriptionName] is a group name the
 * provider chose, not its URL.
 *
 * @param missingGeoFiles `requiredGeoFiles() - installedFileNames()` (spec §4.3) — non-empty
 *   means activation is blocked until these are downloaded. The only input [canActivate] reads.
 * @param hasFailedGeoUpdate whether any geo file this rule set needs last failed to download or
 *   install — the "Last update failed" marker. Deliberately independent of [missingGeoFiles]: a
 *   set can be simultaneously activatable (an older, still-working file is installed) and
 *   showing a failed refresh, or blocked with no failure recorded yet (a file simply never
 *   attempted). Conflating the two markers — treating a failed refresh as blocking, or a missing
 *   file as a mere warning — is the defect this screen exists to avoid.
 * @param assetState this set's **own** generation state, a third axis alongside the two above and
 *   not a summary of them. [RuleSetAssetState.Pending] means a generation is materialising right
 *   now; [RuleSetAssetState.Failed] is §A.3.1's persistent marker, which clears on a successful
 *   retry or on deletion and on nothing else — notably not on app restart.
 * @param assetFailure why the last generation did not land, or null. Null with
 *   [RuleSetAssetState.Failed] cannot happen (the repository writes the pair in one statement),
 *   and null with any other state is simply "nothing has failed".
 * @param downloadProgress non-null only while this set's generation is actually downloading. It
 *   is deliberately not persisted: a bar restored from Room after a process death would describe
 *   a coroutine that no longer exists and could never advance.
 * @param isReadOnly derived from [sourceKind], never stored. There is no third state, and a
 *   stored flag could disagree with the provenance it is supposed to follow (spec §4.2).
 */
internal data class RuleSetRow(
    val id: Long,
    val name: String,
    val entryCount: Int,
    val isActive: Boolean,
    val missingGeoFiles: Set<String>,
    val hasFailedGeoUpdate: Boolean = false,
    val sourceKind: RoutingSourceKind? = null,
    val subscriptionName: String? = null,
    val assetState: RuleSetAssetState = RuleSetAssetState.None,
    val assetFailure: RuleSetAssetFailure? = null,
    /** What this profile's typed DNS block does when activated. */
    val dnsState: DnsState = DnsState.None,
    val downloadProgress: GeoProgress? = null,
    /**
     * Whether the published rules reference any geo database at all.
     *
     * A literal-only set has no geo files, so every asset-state line is noise on
     * it — it reported "Geo files ready" about files it does not have.
     */
    val requiresGeoFiles: Boolean = false,
) {
    val canActivate: Boolean get() = missingGeoFiles.isEmpty()

    /** Imported profiles are edited by duplicating them (spec §4.2). */
    val isReadOnly: Boolean get() = sourceKind != null
}
