// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.sync

import art.yniyniyni.subspace.core.network.FetchFailure

/**
 * What one sync did.
 *
 * §10.4: the UI needs a diagnosis, not a boolean. "Imported 0 of 1" was M3's
 * device-run lesson — a count that says nothing about *why* is a count nobody
 * can act on.
 */
public sealed interface SyncResult {
    /**
     * @property added derived from what the transaction actually wrote, not from what the
     *   response parsed to (Task 11 review fix, Important 2) — a server dropped by
     *   [duplicatesDropped] below does not count here even though it was parsed.
     * @property keptActive servers the provider dropped that were kept because
     *   they were active (spec D4). Non-zero means the UI must show the
     *   "no longer offered by this provider" flag.
     * @property rejectedDirectives how many directives failed validation. The
     *   keys are logged; the values never are (§5.6).
     * @property duplicatesDropped spec §4.3's documented, bounded limitation: how many parsed
     *   servers were not written because their outbound was byte-identical to another server's
     *   (under a different name) already claiming the same identity slot this sync. Zero in the
     *   overwhelming majority of syncs — a provider serving a genuine duplicate is rare, but
     *   when it happens this is what makes "eleven where the provider listed twelve" diagnosable
     *   instead of silent.
     */
    public data class Synced(
        val added: Int,
        val updated: Int,
        val removed: Int,
        val keptActive: Int,
        val rejectedDirectives: Int,
        val duplicatesDropped: Int = 0,
    ) : SyncResult

    public data class Failed(val reason: FetchFailure) : SyncResult

    /**
     * The fetch succeeded and the body yielded no servers.
     *
     * Distinct from [Failed] because the fix is different: the provider served
     * something, it just was not a subscription this client can read.
     * **Stored servers are left untouched** — a provider's momentary template
     * bug must not wipe the user's list.
     *
     * @property detail the parser's own redacted failure reason. Never the body.
     */
    public data class NoServers(val detail: String) : SyncResult
}
