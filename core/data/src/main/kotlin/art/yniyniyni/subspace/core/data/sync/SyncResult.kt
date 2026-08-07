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
     * @property keptActive servers the provider dropped that were kept because
     *   they were active (spec D4). Non-zero means the UI must show the
     *   "no longer offered by this provider" flag.
     * @property rejectedDirectives how many directives failed validation. The
     *   keys are logged; the values never are (§5.6).
     */
    public data class Synced(
        val added: Int,
        val updated: Int,
        val removed: Int,
        val keptActive: Int,
        val rejectedDirectives: Int,
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
