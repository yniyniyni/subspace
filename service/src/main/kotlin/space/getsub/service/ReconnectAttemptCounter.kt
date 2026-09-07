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
 * Not thread-safe on its own. `TunnelService` guards every call with its own
 * `lock`, the same way it guards `generation`/`currentState`/every other
 * shared mutable field.
 */
internal class ReconnectAttemptCounter {
    private var attempt = 0

    /**
     * The number the attempt about to be published should carry. Call once per
     * failure, immediately before publishing [space.getsub.core.model.ConnectionState.Reconnecting]
     * with it — not before deciding whether this failure is retryable at all.
     */
    fun next(): Int {
        attempt += 1
        return attempt
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
