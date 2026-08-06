// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

/**
 * Transport-level options a share link or Clash entry can carry.
 *
 * Deliberately narrow. ARCHITECTURE.md §6 splits storage by provenance: formats
 * with a bounded, known schema are typed, and a hand-written `config.json` is
 * kept byte-for-byte instead. This type covers the first case only — it is
 * Clash's transport surface, not Xray's, and it is not the place to model
 * Finalmask, fragmentation or noises when those arrive at M8.
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
}
