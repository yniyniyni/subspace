// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

/**
 * The retry loop's attempt counter (spec §2.3/§2.4).
 *
 * Extracted into its own small type rather than a raw field on
 * [TunnelService], for the same reason [ReconnectBackoff] and `reconcile`
 * are: `TunnelService` is a `VpnService` and cannot run on the JVM (§11), so
 * anything that stays a private field there is untestable. Whether a
 * successful connect resets this count is exactly the kind of thing a
 * reviewer catches by reading and a device run never exercises twice in one
 * session — worth a real test.
 *
 * Fix round 2: split into a read-only [peekNext] and an explicit [commit],
 * with **no** combined increment-and-return. `TunnelService` calls a start
 * sequence's `failStart` with a `gen` that can already be stale — the
 * `Tun2Socks.start` failure path guards `tunInterface` with
 * `if (gen == generation)` and calls `failStart(gen, …)` three lines later
 * regardless of that check's outcome — so a superseded call must be able to
 * *read* what the next attempt would be (to label the trial `Reconnecting`
 * state and the backoff delay) without that read being able to leave a
 * lasting increment behind if the generation turns out to be stale.
 * [TunnelService] therefore calls [peekNext] freely, but calls [commit] only
 * from inside `TerminalOutcome.settle`'s generation-gated `lifecycle` —
 * exactly where every other mutation this counter's siblings
 * (`controller`, `configFile`, `liveSession`) already lives.
 *
 * Not thread-safe on its own. `TunnelService` guards every call with its own
 * `lock`, the same way it guards `generation`/`currentState`/every other
 * shared mutable field.
 */
internal class ReconnectAttemptCounter {
    private var attempt = 0

    /**
     * The number the *next* attempt would carry, without committing to it.
     * Safe to call from a generation that later turns out to be stale — it
     * mutates nothing, so a call with no matching [commit] leaves no trace.
     */
    fun peekNext(): Int = attempt + 1

    /**
     * Makes [next] the current count. The only mutator — call it only once
     * the caller has confirmed (by whatever means — a generation check, a
     * committed `TerminalOutcome.settle`) that this attempt is the one that
     * actually happened, or a superseded generation's trial value becomes
     * durable state for a session it does not own.
     */
    fun commit(next: Int) {
        attempt = next
    }

    /**
     * Spec §2.3/§2.4: a successful connect, an explicit disconnect or revoke,
     * or a terminal failure all start the *next* retry sequence counting from
     * zero. A counter that never resets turns the second outage of a
     * long-lived session into an immediate give-up, since it would inherit
     * whatever count the first outage left behind.
     */
    fun reset() {
        attempt = 0
    }
}
