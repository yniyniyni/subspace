// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

/**
 * Whether the tunnel is up.
 *
 * ARCHITECTURE.md §5.5: this lives in the service and is published to the UI over
 * IPC. The UI never infers it from a local boolean, and after process death it
 * rebinds and re-reads the real value — an app showing "Disconnected" while the
 * tunnel is up is worse than one that crashes.
 */
public sealed interface ConnectionState {
    public data object Disconnected : ConnectionState

    public data class Connecting(
        val stage: StartupStage,
    ) : ConnectionState

    public data class Connected(
        val sinceEpochMillis: Long,
        val socksPort: Int,
    ) : ConnectionState

    public data object Disconnecting : ConnectionState

    /**
     * A terminal failure, carrying a **redacted** [detail].
     *
     * Build these with [failure]. That is not a style note: the constructor is
     * genuinely `private`, so `ConnectionState.Failed(reason, raw)` does not
     * compile from any file, in or out of this module. [ConsistentCopyVisibility]
     * closes the matching hole in the generated `copy()` — on this Kotlin
     * version a data class with a private constructor still gets a *public*
     * `copy()` without the annotation, which would let
     * `existingFailure.copy(detail = raw)` rebuild an unredacted instance
     * without ever calling [failure].
     *
     * This matters more here than it did for `ParseFailure` in `:core:parser`,
     * which got the same treatment: [detail] originates in `XrayException`,
     * whose own KDoc warns the core quotes the config back at you (§5.6),
     * where a `ParseFailure`'s detail is structured text our own validators
     * wrote.
     */
    @ConsistentCopyVisibility
    public data class Failed private constructor(
        val reason: FailureReason,
        val detail: String,
    ) : ConnectionState {
        internal companion object {
            // Only reachable from failure() below, which is the sole public
            // entry point. Internal rather than private because a private
            // constructor is scoped to this class body, and this companion is
            // how failure() — a top-level function in the same file but not in
            // this class — reaches it. Mirrors ParseFailure.redacted.
            internal fun redacted(
                reason: FailureReason,
                detail: String,
            ): Failed = Failed(reason, redact(detail))
        }
    }
}

/**
 * How far the start sequence got.
 *
 * Granular on purpose. §10.4 warns that a swallowed start-sequence failure
 * produces "UI says connected, nothing works, no log line" — the stage says where
 * it broke without needing a log, which matters because §5.6 forbids logging the
 * one thing that would otherwise identify the problem.
 */
public enum class StartupStage {
    AllocatingPort,
    GeneratingConfig,
    ValidatingConfig,
    StartingCore,
    EstablishingTun,
    StartingTunnel,
}

public enum class FailureReason {
    ConfigGenerationFailed,
    ConfigRejected,
    PortAllocationFailed,
    CoreStartFailed,
    VpnPermissionMissing,
    TunEstablishFailed,
    TunnelStartFailed,
    Revoked,

    /**
     * The profile parsed cleanly but names a protocol `:core:xray` cannot emit
     * yet. M2 added five protocols to the model; the generator still writes only
     * VLESS. Distinct from ConfigGenerationFailed because the user can act on it
     * — pick a different server — and a generic config error would send them
     * looking for a bug that is not there.
     */
    ProtocolNotSupported,

    /**
     * The service was handed a profile it could not decode — a protocol or
     * security discriminant it has no name for.
     *
     * Distinct from [ProtocolNotSupported], which means the profile decoded
     * fine and names a protocol the generator cannot emit yet. This one means
     * the bytes themselves were unreadable, so nothing is known about the
     * server at all. Refusing is the point: guessing a default would connect
     * to something other than what the user chose.
     */
    ProfileDecodeFailed,
}

/**
 * The only supported way to build a [ConnectionState.Failed].
 *
 * Redaction happens here rather than at log time because redaction that depends
 * on remembering to call a helper is redaction that eventually fails (§5.6), and
 * libXray's error strings quote the config back at you.
 *
 * "Only supported" is now "only possible": see the note on
 * [ConnectionState.Failed] for why its constructor and `copy()` are closed.
 */
public fun failure(
    reason: FailureReason,
    detail: String,
): ConnectionState.Failed = ConnectionState.Failed.redacted(reason, detail)
