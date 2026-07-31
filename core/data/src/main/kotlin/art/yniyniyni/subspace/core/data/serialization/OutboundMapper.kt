// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.serialization

import art.yniyniyni.subspace.core.model.Outbound
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.ShadowsocksOutbound
import art.yniyniyni.subspace.core.model.SocksOutbound
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.TransportOptions
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.model.VmessOutbound
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// encodeDefaults: a decoded StreamDto.transport must serialize back out even
// when it is the default TransportDto.None, or identityHashOf(outbound) would
// change for existing rows the moment a caller round-trips through toOutbound()
// and toJson() again.
private val json = Json { encodeDefaults = true }

/**
 * Serializes this outbound to its persisted JSON form.
 *
 * Property order is kotlinx.serialization's declaration order, which is fixed
 * per type, so the same [Outbound] always encodes to the same bytes — that
 * determinism is what makes [identityHashOf] stable.
 */
internal fun Outbound.toJson(): String = json.encodeToString(OutboundDto.serializer(), toDto())

/**
 * Parses a persisted outbound, or `null` if [this] is not readable as one.
 *
 * Never throws: `:core:data` sits behind the same never-throw expectation as
 * `:core:parser` (ARCHITECTURE.md §7), so a row that fails to decode must not
 * take the whole list down with it.
 */
internal fun String.toOutbound(): Outbound? =
    try {
        json.decodeFromString(OutboundDto.serializer(), this).toDomain()
    } catch (_: SerializationException) {
        // SerializationException covers both malformed JSON and a JSON value
        // that is well-formed but does not match any OutboundDto shape (e.g.
        // an unknown @SerialName discriminator) — kotlinx.serialization's
        // SerializationException is a subclass of IllegalArgumentException,
        // so this one catch is exhaustive for decode failures.
        null
    }

private fun Outbound.toDto(): OutboundDto =
    when (this) {
        is VlessOutbound ->
            OutboundDto.Vless(
                address = address,
                port = port,
                uuid = uuid,
                flow = flow,
                stream = stream.toDto(),
            )

        is VmessOutbound ->
            OutboundDto.Vmess(
                address = address,
                port = port,
                uuid = uuid,
                alterId = alterId,
                security = security,
                stream = stream.toDto(),
            )

        is TrojanOutbound ->
            OutboundDto.Trojan(
                address = address,
                port = port,
                password = password,
                stream = stream.toDto(),
            )

        is ShadowsocksOutbound ->
            OutboundDto.Shadowsocks(
                address = address,
                port = port,
                method = method,
                password = password,
            )

        is SocksOutbound ->
            OutboundDto.Socks(
                address = address,
                port = port,
                username = username,
                password = password,
            )
    }

private fun OutboundDto.toDomain(): Outbound =
    when (this) {
        is OutboundDto.Vless ->
            VlessOutbound(
                address = address,
                port = port,
                uuid = uuid,
                flow = flow,
                stream = stream.toDomain(),
            )

        is OutboundDto.Vmess ->
            VmessOutbound(
                address = address,
                port = port,
                uuid = uuid,
                alterId = alterId,
                security = security,
                stream = stream.toDomain(),
            )

        is OutboundDto.Trojan ->
            TrojanOutbound(
                address = address,
                port = port,
                password = password,
                stream = stream.toDomain(),
            )

        is OutboundDto.Shadowsocks ->
            ShadowsocksOutbound(
                address = address,
                port = port,
                method = method,
                password = password,
            )

        is OutboundDto.Socks ->
            SocksOutbound(
                address = address,
                port = port,
                username = username,
                password = password,
            )
    }

private fun StreamSettings.toDto(): StreamDto =
    StreamDto(
        network = network,
        security = security.toDto(),
        transport = transport.toDto(),
    )

private fun StreamDto.toDomain(): StreamSettings =
    StreamSettings(
        network = network,
        security = security.toDomain(),
        transport = transport.toDomain(),
    )

private fun Security.toDto(): SecurityDto =
    when (this) {
        is Security.None -> SecurityDto.None
        is Security.Reality ->
            SecurityDto.Reality(
                serverName = serverName,
                publicKey = publicKey,
                shortId = shortId,
                fingerprint = fingerprint,
                spiderX = spiderX,
            )

        is Security.Tls ->
            SecurityDto.Tls(
                serverName = serverName,
                fingerprint = fingerprint,
                allowInsecure = allowInsecure,
            )
    }

private fun SecurityDto.toDomain(): Security =
    when (this) {
        is SecurityDto.None -> Security.None
        is SecurityDto.Reality ->
            Security.Reality(
                serverName = serverName,
                publicKey = publicKey,
                shortId = shortId,
                fingerprint = fingerprint,
                spiderX = spiderX,
            )

        is SecurityDto.Tls ->
            Security.Tls(
                serverName = serverName,
                fingerprint = fingerprint,
                allowInsecure = allowInsecure,
            )
    }

private fun TransportOptions.toDto(): TransportDto =
    when (this) {
        is TransportOptions.None -> TransportDto.None
        is TransportOptions.WebSocket -> TransportDto.WebSocket(path = path, headers = headers)
        is TransportOptions.Grpc -> TransportDto.Grpc(serviceName = serviceName)
    }

private fun TransportDto.toDomain(): TransportOptions =
    when (this) {
        is TransportDto.None -> TransportOptions.None
        is TransportDto.WebSocket -> TransportOptions.WebSocket(path = path, headers = headers)
        is TransportDto.Grpc -> TransportOptions.Grpc(serviceName = serviceName)
    }
