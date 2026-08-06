// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

/**
 * Transport-level options a share link, Clash entry or raw Xray config can carry.
 *
 * Still narrow, and still for the same reason. ARCHITECTURE.md §6 splits storage by
 * provenance: formats with a bounded, known schema are typed, and a hand-written
 * `config.json` is kept byte-for-byte instead. This models only what a bounded format
 * expresses, and it is not the place to model Finalmask, fragmentation or noises when
 * those arrive at M8.
 *
 * [Xhttp] widens that surface past Clash's, which the earlier version of this note called
 * the boundary. It has to: `xhttp` is the transport `vless://` links in the wild actually
 * carry, and leaving it unmodelled is what made a working xhttp server present as "not
 * supported by this build yet" — the whole point of typing a transport is that
 * [art.yniyniyni.subspace.core.xray.XrayConfigGenerator] can emit it again.
 */
public sealed interface TransportOptions {
    /** `tcp`, or a transport whose options the source did not specify. */
    public data object None : TransportOptions

    public data class WebSocket(
        val path: String,
        /** Usually just `Host`. Empty when the source set none. */
        val headers: Map<String, String>,
    ) : TransportOptions

    public data class Grpc(
        val serviceName: String,
    ) : TransportOptions

    /**
     * XHTTP, Xray's own transport — `network: "xhttp"`, aliased to `splithttp` internally.
     *
     * Only the three fields a share link carries. `XHTTPObject` has some thirty more
     * (padding placement, session-id tables, uplink chunking); every one of them left
     * unset takes Xray's own default, which is what a link that does not mention them
     * means. Do not widen this to cover the rest without a format that actually
     * expresses them — §10.5, and an invented key can reject the whole config.
     *
     * @property path Verified against Xray-core v26.7.11 (`infra/conf/transport_method.go`,
     *   `SplitHTTPConfig`): empty normalises to `/` at dial time, so `"/"` is a faithful
     *   default rather than a guess.
     * @property host The `Host` header. Null when the source set none, and then Xray falls
     *   back to the dial address — so null and "same as address" are the same request on
     *   the wire, and this does not have to invent one.
     * @property mode `auto`, `packet-up`, `stream-up`, `stream-one`. Null when unset, which
     *   Xray reads as `auto`.
     */
    public data class Xhttp(
        val path: String,
        val host: String?,
        val mode: String?,
    ) : TransportOptions
}
