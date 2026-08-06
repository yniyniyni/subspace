// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.serialization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The persisted shape of [art.yniyniyni.subspace.core.model.Outbound].
 *
 * A deliberate surrogate, not the domain model itself: M3 widened
 * `StreamSettings` once already and M8 will widen it again for fragmentation
 * and noises, and a storage format welded to the domain model turns every
 * such change into a migration. [OutboundMapper] is the only place that knows
 * both shapes.
 */
@Serializable
internal sealed interface OutboundDto {
    @Serializable
    @SerialName("vless")
    data class Vless(
        val address: String,
        val port: Int,
        val uuid: String,
        val flow: String? = null,
        val stream: StreamDto,
    ) : OutboundDto

    @Serializable
    @SerialName("vmess")
    data class Vmess(
        val address: String,
        val port: Int,
        val uuid: String,
        val alterId: Int,
        val security: String,
        val stream: StreamDto,
    ) : OutboundDto

    @Serializable
    @SerialName("trojan")
    data class Trojan(
        val address: String,
        val port: Int,
        val password: String,
        val stream: StreamDto,
    ) : OutboundDto

    @Serializable
    @SerialName("shadowsocks")
    data class Shadowsocks(
        val address: String,
        val port: Int,
        val method: String,
        val password: String,
    ) : OutboundDto

    @Serializable
    @SerialName("socks")
    data class Socks(
        val address: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
    ) : OutboundDto
}

@Serializable
internal data class StreamDto(
    val network: String,
    val security: SecurityDto,
    val transport: TransportDto = TransportDto.None,
)

@Serializable
internal sealed interface SecurityDto {
    @Serializable
    @SerialName("none")
    data object None : SecurityDto

    @Serializable
    @SerialName("reality")
    data class Reality(
        val serverName: String,
        val publicKey: String,
        val shortId: String,
        val fingerprint: String,
        val spiderX: String,
    ) : SecurityDto

    @Serializable
    @SerialName("tls")
    data class Tls(
        val serverName: String,
        val fingerprint: String,
        val allowInsecure: Boolean,
    ) : SecurityDto
}

@Serializable
internal sealed interface TransportDto {
    @Serializable
    @SerialName("none")
    data object None : TransportDto

    @Serializable
    @SerialName("ws")
    data class WebSocket(
        val path: String,
        val headers: Map<String, String>,
    ) : TransportDto

    @Serializable
    @SerialName("grpc")
    data class Grpc(
        val serviceName: String,
    ) : TransportDto
}
