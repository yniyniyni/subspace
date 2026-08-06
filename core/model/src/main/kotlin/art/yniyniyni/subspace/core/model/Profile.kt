// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

/**
 * A user-supplied server.
 *
 * ARCHITECTURE.md §6: this is **not** an Xray config. `:core:xray` generates the
 * JSON at connect time from this plus the runtime settings.
 *
 * M2 widened [outbound] from a concrete VLESS type to the sealed [Outbound],
 * because `:core:parser` produces five protocols. An earlier version of this
 * note said "M3 reshapes this for Room — so resist generalising it now"; that
 * assumed M3 would be the first milestone to need generality, and M2 was.
 * M3 still reshapes this for Room.
 *
 * @property rawJson Element-provenance fix (device-fixes finding): non-null only for a
 *   profile parsed out of a hand-written Xray config, and then it is the exact bytes of the
 *   *element* that produced it — the whole document for a bare top-level object, or that one
 *   array entry when the document is a top-level array of configs (`XrayJson.kt`). `:core:data`
 *   stores this as `kind = RAW_JSON`'s `rawJson` column and derives identity from it (§6),
 *   falling back to [outbound]-based identity when several profiles share one element's bytes
 *   (one element with several outbounds) — see `ProfileRepository.import`'s KDoc. Every other
 *   container (share links, base64 lists, Clash YAML) has a bounded field set and leaves this
 *   `null`, landing as `kind = TYPED` instead.
 */
public data class Profile(
    val id: String,
    val name: String,
    val outbound: Outbound,
    val rawJson: String? = null,
)

/**
 * One server, in one protocol.
 *
 * Sealed so a `when` that misses a protocol fails to compile. `:core:xray`
 * currently emits only [VlessOutbound] and must refuse the rest explicitly —
 * a profile that silently generates a config connecting to nothing is worse
 * than one that says it is unsupported.
 */
public sealed interface Outbound {
    public val address: String
    public val port: Int
}

public data class VlessOutbound(
    override val address: String,
    override val port: Int,
    val uuid: String,
    /** XTLS flow control, e.g. `xtls-rprx-vision`. Null when unset. */
    val flow: String?,
    val stream: StreamSettings,
) : Outbound

public data class VmessOutbound(
    override val address: String,
    override val port: Int,
    val uuid: String,
    /** Legacy AlterID. 0 on every modern server; non-zero means VMessAEAD is off. */
    val alterId: Int,
    /** `auto`, `aes-128-gcm`, `chacha20-poly1305`, `none`. */
    val security: String,
    val stream: StreamSettings,
) : Outbound

public data class TrojanOutbound(
    override val address: String,
    override val port: Int,
    val password: String,
    val stream: StreamSettings,
) : Outbound

public data class ShadowsocksOutbound(
    override val address: String,
    override val port: Int,
    /** e.g. `aes-256-gcm`, `chacha20-ietf-poly1305`, `2022-blake3-aes-256-gcm`. */
    val method: String,
    val password: String,
) : Outbound

public data class SocksOutbound(
    override val address: String,
    override val port: Int,
    val username: String?,
    val password: String?,
) : Outbound

public data class StreamSettings(
    /** Xray transport: `tcp`, `ws`, `grpc`, … M1 exercises `tcp`. */
    val network: String,
    val security: Security,
    /**
     * Options belonging to [network]. Defaulted so every existing construction
     * site stays valid — M2's parsers set only `network` and `security`, and
     * they are correct to, since the formats they read carried nothing more.
     */
    val transport: TransportOptions = TransportOptions.None,
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
