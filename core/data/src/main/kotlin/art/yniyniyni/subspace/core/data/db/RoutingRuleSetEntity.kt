// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One named routing rule set.
 *
 * Six `TEXT` columns of newline-separated entries rather than a serialised blob.
 * An entry can never contain a newline — `RoutingEntries.problemWith` rejects
 * whitespace in domains and addresses — so no serialisation library is needed
 * (§10.7), and the column stays legible in a database dump.
 *
 * [name] is uniquely indexed because M6 defines importing a profile whose name
 * already exists as an **update**, not a duplicate (§A.3.1). Enforcing that in
 * the schema now is what stops M6 from needing a migration.
 *
 * §5.6: the six entry columns are the domains and addresses the user visits.
 * The generated `toString()` would print them verbatim — see the override.
 */
@Entity(
    tableName = "routing_rule_sets",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["subscriptionId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class RoutingRuleSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val directSites: String,
    val directIps: String,
    val proxySites: String,
    val proxyIps: String,
    val blockSites: String,
    val blockIps: String,
    /** [art.yniyniyni.subspace.core.model.RouteOutcome] names, comma-separated. */
    val routeOrder: String,
    /** A [art.yniyniyni.subspace.core.model.DomainStrategy] name. */
    val domainStrategy: String,
    val createdAt: Long,
    /** A `RoutingSourceKind.wireValue`, or null when the user made this set here. */
    val sourceKind: String? = null,
    /** The subscription that delivered it. `ON DELETE CASCADE` is §A.1's rule. */
    val subscriptionId: Long? = null,
    /** The profile's own `LastUpdated`, unix seconds. The monotonicity gate (spec §7.3). */
    val lastUpdated: Long? = null,
    /** `RoutingProfile.fingerprint()`. Drives the silent-no-op rule (spec §7.3). */
    val fingerprint: String? = null,
    val geoIpUrl: String? = null,
    val geoSiteUrl: String? = null,
    /** Null preserves proxy-by-default; false emits a catch-all to `direct`. */
    val globalProxy: Boolean? = null,
    /**
     * The profile's DNS block, canonicalised through
     * [art.yniyniyni.subspace.core.data.serialization.ProfileDnsCodec] in Happ's
     * own key names, so an M6-era row still decodes (M6.5).
     */
    val dnsJson: String? = null,
    /** Stored, never read. Research §3.3. */
    val useChunkFiles: Boolean? = null,
    /** Which generation directory under `geo/sets/<id>/` is live (spec §7.4). */
    @ColumnInfo(defaultValue = "0")
    val assetGeneration: Long = 0,
    /** A `RuleSetAssetState` name. */
    @ColumnInfo(defaultValue = "'None'")
    val assetState: String = "None",
    /** A closed-vocabulary failure name, or null. §A.3.1's persistent error marker. */
    val assetFailure: String? = null,
) {
    /**
     * §5.6. The six entry columns are the domains and addresses the user visits;
     * [geoIpUrl], [geoSiteUrl] and [dnsJson] are provider-chosen hostnames. None
     * of them reach a log line. [assetState] and [globalProxy] are modes, not
     * data, so they stay legible — a redacted entity that says nothing useful is
     * how a redaction rule gets deleted by the next person to debug this.
     */
    override fun toString(): String =
        "RoutingRuleSetEntity(id=$id, name=$name, routeOrder=$routeOrder, " +
            "domainStrategy=$domainStrategy, globalProxy=$globalProxy, sourceKind=$sourceKind, " +
            "subscriptionId=$subscriptionId, lastUpdated=$lastUpdated, " +
            "assetGeneration=$assetGeneration, assetState=$assetState, " +
            "assetFailure=$assetFailure, entries=<redacted>, urls=<redacted>, dns=<redacted>)"
}
