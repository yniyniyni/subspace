// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

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
)

/**
 * One rule set's row.
 *
 * §5.6: nothing here carries an entry, a site or an IP — only a name (which the user chose to
 * label the set, not a value it routes) and a count.
 *
 * @param missingGeoFiles `requiredGeoFiles() - installedFileNames()` (spec §4.3) — non-empty
 *   means activation is blocked until these are downloaded. The only input [canActivate] reads.
 * @param hasFailedGeoUpdate whether any geo file this rule set needs last failed to download or
 *   install — the "Last update failed" marker. Deliberately independent of [missingGeoFiles]: a
 *   set can be simultaneously activatable (an older, still-working file is installed) and
 *   showing a failed refresh, or blocked with no failure recorded yet (a file simply never
 *   attempted). Conflating the two markers — treating a failed refresh as blocking, or a missing
 *   file as a mere warning — is the defect this screen exists to avoid.
 */
internal data class RuleSetRow(
    val id: Long,
    val name: String,
    val entryCount: Int,
    val isActive: Boolean,
    val missingGeoFiles: Set<String>,
    val hasFailedGeoUpdate: Boolean = false,
) {
    val canActivate: Boolean get() = missingGeoFiles.isEmpty()
}
