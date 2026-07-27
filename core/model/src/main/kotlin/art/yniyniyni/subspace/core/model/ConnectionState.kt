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

    /** [detail] is redacted at construction — build these with [failure], never directly. */
    public data class Failed(
        val reason: FailureReason,
        val detail: String,
    ) : ConnectionState
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
}

/**
 * The only supported way to build a [ConnectionState.Failed].
 *
 * Redaction happens here rather than at log time because redaction that depends
 * on remembering to call a helper is redaction that eventually fails (§5.6), and
 * libXray's error strings quote the config back at you.
 */
public fun failure(
    reason: FailureReason,
    detail: String,
): ConnectionState.Failed = ConnectionState.Failed(reason, redact(detail))
