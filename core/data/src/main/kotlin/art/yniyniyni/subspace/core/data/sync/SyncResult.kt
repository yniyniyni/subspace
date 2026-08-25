// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data.sync

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
     *   "no longer offered by this provider" flag. **Known cosmetic limitation** (Task 11
     *   review round 2, recorded not fixed): if the provider actually renamed the active
     *   server rather than dropping it, reconciliation has no way to tell "renamed" from
     *   "withdrawn" — both look identical from outside (the old `subscriptionKey` is simply
     *   absent from the new response) — so the row is flagged "no longer offered" even though
     *   the provider is still offering it under a new name. Unavoidable under key-based
     *   reconciliation; the flag clears the moment the user switches to a different active
     *   profile and the row is naturally deleted or re-matched on the next sync.
     * @property rejectedDirectives how many directives failed validation. The
     *   keys are logged; the values never are (§5.6).
     * @property duplicatesDropped spec §4.3's documented, bounded limitation: how many parsed
     *   servers were not written because their outbound was byte-identical to another server's
     *   (under a different name) already claiming the same identity slot this sync. Zero in the
     *   overwhelming majority of syncs — a provider serving a genuine duplicate is rare, but
     *   when it happens this is what makes "eleven where the provider listed twelve" diagnosable
     *   instead of silent. **Known bounded limitation** (Task 11 review round 2, recorded not
     *   fixed): the row backing the *losing* entry is left holding its previous sync's content
     *   until the collision stops recurring — every sync that still finds the same two response
     *   entries colliding drops the same loser again, so a genuinely renamed server behind a
     *   losing key is never imported while the winner keeps claiming the identity first. This
     *   self-heals as soon as the response stops colliding (the provider fixes the duplicate, or
     *   — in the active-row variant `insertSubscriptionProfile`'s KDoc describes — the tunnel
     *   moves off the row that keeps winning the claim).
     */
    public data class Synced(
        val added: Int,
        val updated: Int,
        val removed: Int,
        val keptActive: Int,
        val rejectedDirectives: Int,
        val duplicatesDropped: Int = 0,
    ) : SyncResult

    /**
     * @property reason [SubscriptionSyncFailure], not `:core:network`'s `FetchFailure` directly
     *   — see that type's own KDoc for why the translation happens at this boundary.
     */
    public data class Failed(val reason: SubscriptionSyncFailure) : SyncResult

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

    /**
     * The transaction aborted on an identity collision `SubscriptionSyncer` did not resolve in
     * Kotlin before persisting (Task 11 review round 2's backstop). `SubscriptionDao.applySync`
     * rolls back atomically on any thrown exception, so **stored servers are left untouched** —
     * this is a "nothing happened" outcome, not a partial one. Exists alongside the Kotlin-side
     * duplicate resolution in `SubscriptionSyncer.buildUpserts`/`reconcile`, not instead of it:
     * that resolves every collision shape identified so far; this is the net under it for one
     * neither of us has thought of yet, so the sync fails loudly and the tunnel is unaffected
     * rather than the process crashing on an uncaught `SQLiteConstraintException`.
     *
     * @property detail a closed, redacted description — never the exception message, which can
     *   quote this table's column values (§5.6, `ProfileRepository.move`'s KDoc explains the
     *   same hazard for the same exception type).
     */
    public data class ReconciliationConflict(val detail: String) : SyncResult
}
