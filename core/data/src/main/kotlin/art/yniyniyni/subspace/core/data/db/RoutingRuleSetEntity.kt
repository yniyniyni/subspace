// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.Entity
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
    indices = [Index(value = ["name"], unique = true)],
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
) {
    override fun toString(): String =
        "RoutingRuleSetEntity(id=$id, name=$name, routeOrder=$routeOrder, " +
            "domainStrategy=$domainStrategy, entries=<redacted>)"
}
