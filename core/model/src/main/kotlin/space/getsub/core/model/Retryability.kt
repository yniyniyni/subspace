// SPDX-License-Identifier: AGPL-3.0-or-later
package space.getsub.core.model

/**
 * Whether a [FailureReason] is worth reconnecting through.
 *
 * ARCHITECTURE.md §10.4: a swallowed start-sequence failure produces "UI says
 * connected, nothing works, no log line". Retrying forever on a config the core
 * has already refused is that same failure with a spinner on it — the user sees
 * activity and has no way to learn that nothing will change.
 */
public enum class Retryability {
    /** Clear session intent, publish `Failed`, stop. Only the user can act. */
    Terminal,

    /** Hold intent, publish `Reconnecting`, back off. Unbounded while a network exists. */
    Retryable,

    /** As [Retryable], but bounded by [TUN_ESTABLISH_ATTEMPT_CAP]. See [retryability]. */
    RetryableCapped,
}

/**
 * How many `establish()` failures are absorbed before the session is treated as
 * terminal.
 *
 * Spec §2.3: `establish()` returns null both under resource pressure and when
 * VPN consent has quietly gone, and nothing at that call site tells them apart.
 * Small on purpose — an unbounded retry against revoked consent never succeeds.
 */
public const val TUN_ESTABLISH_ATTEMPT_CAP: Int = 3

/**
 * Spec §2.2.
 *
 * Deliberately has **no `else` branch**: a `FailureReason` added later must not
 * fall into a default that silently absorbs it. The compiler is the check, the
 * same way [space.getsub.service.ConnectionStateParcel]'s explicit int
 * discriminant makes adding a state a deliberate change on both sides.
 */
@Suppress("CyclomaticComplexMethod") // One arm per enum member; splitting would hide the exhaustiveness.
public fun FailureReason.retryability(): Retryability =
    when (this) {
        // Config, assets or consent. Retrying cannot change the answer.
        FailureReason.VpnPermissionMissing,
        FailureReason.ProfileDecodeFailed,
        FailureReason.ProtocolNotSupported,
        FailureReason.ConfigGenerationFailed,
        FailureReason.ConfigRejected,
        FailureReason.PassthroughRejectedAtConnect,
        FailureReason.PassthroughOverrideUnavailable,
        FailureReason.PerAppAllowListEmpty,
        FailureReason.GeoDataMissing,
        FailureReason.Revoked,
        -> Retryability.Terminal

        // Resource contention. The next attempt may well succeed.
        FailureReason.CoreStartFailed,
        FailureReason.TunnelStartFailed,
        FailureReason.PortAllocationFailed,
        -> Retryability.Retryable

        FailureReason.TunEstablishFailed -> Retryability.RetryableCapped
    }
