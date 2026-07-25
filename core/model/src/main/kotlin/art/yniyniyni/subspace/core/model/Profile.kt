// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

/**
 * A user-supplied server.
 *
 * ARCHITECTURE.md §6: this is **not** an Xray config. `:core:xray` generates the
 * JSON at connect time from this plus the runtime settings.
 *
 * M1 models VLESS only. Every other protocol arrives with `:core:parser` in M2,
 * and M3 reshapes this for Room — so resist generalising it now.
 */
public data class Profile(
    val id: String,
    val name: String,
    val outbound: VlessOutbound,
)

public data class VlessOutbound(
    val address: String,
    val port: Int,
    val uuid: String,
    /** XTLS flow control, e.g. `xtls-rprx-vision`. Null when unset. */
    val flow: String?,
    val stream: StreamSettings,
)

public data class StreamSettings(
    /** Xray transport: `tcp`, `ws`, `grpc`, … M1 exercises `tcp`. */
    val network: String,
    val security: Security,
)

public sealed interface Security {
    public data object None : Security

    /** REALITY — the TLS-camouflage transport (§13). */
    public data class Reality(
        val serverName: String,
        val publicKey: String,
        val shortId: String,
        val fingerprint: String,
        val spiderX: String,
    ) : Security

    public data class Tls(
        val serverName: String,
        val fingerprint: String,
        val allowInsecure: Boolean,
    ) : Security
}
