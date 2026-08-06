// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.ShadowsocksOutbound
import art.yniyniyni.subspace.core.model.SocksOutbound
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.model.VmessOutbound
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar

/**
 * Clash / Clash.Meta `proxies:` lists.
 *
 * Walked as a YAML node tree rather than decoded into `@Serializable` classes:
 * Clash configs contain rules, proxy-groups, DNS blocks and provider stanzas we
 * do not model, and proxy entries differ by `type` — a strict decoder would
 * reject whole files over fields we never read.
 *
 * kaml sits on snakeyaml-engine (YAML 1.2), which does not instantiate arbitrary
 * types named in the document. That matters here specifically: subscription
 * content arrives over the network and is attacker-controlled, and the classic
 * YAML RCE (CVE-2022-1471) is exactly a type-instantiation gadget.
 *
 * Only `vmess`, `trojan`, `ss`, `vless` and `socks5` entries become profiles.
 * Everything else — `hysteria`, `wireguard`, proxy providers — is reported as
 * an unsupported type rather than skipped, so a subscription that yields
 * nothing says why. `ws-opts` and `grpc-opts` feed `TransportOptions` (see
 * `ClashTransport.kt`) for `vless` only; the other protocols still carry
 * [StreamSettings]'s transport name alone (SOCKS has no transport surface at
 * all), a limitation this file's history predates and does not change.
 *
 * Every node read goes through `node`/`text` in `ClashYamlNode.kt` and a safe
 * cast rather than kaml's `YamlMap.get<T>` — see that file for why.
 */
@Suppress("ReturnCount")
internal fun parseClashYaml(text: String): ParseOutcome {
    val root = parseYamlMap(text)
    if (root == null) {
        val failure =
            parseFailure(
                0,
                ParseFailureReason.MalformedYaml,
                FailureDetail.Malformed(DetailField.YamlBody),
            )
        return ParseOutcome(emptyList(), listOf(failure))
    }

    // An absent `proxies:` is a config we simply have nothing to take from — a
    // rules-only file is legitimate. A present one holding something other than
    // a list is a broken Clash config, and saying so beats returning silence.
    val proxiesNode = root.node("proxies") ?: return ParseOutcome.EMPTY
    if (proxiesNode is YamlNull) return ParseOutcome.EMPTY
    val proxies = proxiesNode as? YamlList
    if (proxies == null) {
        val failure =
            parseFailure(
                0,
                ParseFailureReason.MalformedYaml,
                FailureDetail.Malformed(DetailField.YamlBody),
            )
        return ParseOutcome(emptyList(), listOf(failure))
    }

    val profiles = mutableListOf<Profile>()
    val failures = mutableListOf<ParseFailure>()

    proxies.items.forEachIndexed { index, item ->
        val proxy = item as? YamlMap
        if (proxy == null) {
            failures +=
                parseFailure(
                    index,
                    ParseFailureReason.MalformedYaml,
                    FailureDetail.Malformed(DetailField.YamlBody),
                )
            return@forEachIndexed
        }
        when (val result = proxyToProfile(proxy, index)) {
            is LinkResult.Ok -> profiles += result.profile
            is LinkResult.Bad -> failures += result.failure
        }
    }

    return ParseOutcome(profiles, failures)
}

/**
 * kaml translates only the engine's *marked* exceptions into its own
 * [com.charleskorn.kaml.YamlException] hierarchy — `YamlParser` catches
 * `MarkedYamlEngineException` and nothing wider — so an unmarked engine failure
 * escapes `catch (YamlException)` and breaks §7's promise for the whole
 * subscription.
 *
 * That is not hypothetical: a document opening `%YAML 2.0` throws
 * snakeyaml-engine's `YamlVersionException`, which is not a `YamlException` at
 * all. Measured against kaml 0.83.0, not assumed, and `never throws on nasty
 * yaml` keeps it measured — that input fails the test if this catch is narrowed.
 *
 * [Error] deliberately still propagates: a StackOverflowError from a
 * pathologically nested document is not something this parser can honestly
 * report as a malformed proxy entry.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun parseYamlMap(text: String): YamlMap? =
    try {
        Yaml.default.parseToYamlNode(text) as? YamlMap
    } catch (e: Exception) {
        null
    }

@Suppress("ReturnCount")
private fun proxyToProfile(
    proxy: YamlMap,
    index: Int,
): LinkResult {
    val server = proxy.text("server")
    if (server == null) {
        return bad(index, ParseFailureReason.MalformedYaml, FailureDetail.Missing(DetailField.Address))
    }
    val port =
        proxy.text("port")?.toIntOrNull()
            ?: return bad(index, ParseFailureReason.InvalidPort, FailureDetail.Malformed(DetailField.Port))
    validatePort(port)?.let { return bad(index, ParseFailureReason.InvalidPort, it) }

    val name = proxy.text("name") ?: server
    val sni = proxy.text("sni") ?: proxy.text("servername") ?: server
    val allowInsecure = proxy.text("skip-cert-verify") == "true"
    val network = proxy.text("network") ?: "tcp"

    return when (proxy.text("type")) {
        "vmess" -> vmess(proxy, ClashCommon(index, server, port, name, sni, allowInsecure, network))
        "trojan" -> trojan(proxy, ClashCommon(index, server, port, name, sni, allowInsecure, network))
        "ss" -> shadowsocks(proxy, index, server, port, name)
        "vless" -> vless(proxy, ClashCommon(index, server, port, name, sni, allowInsecure, network))
        "socks5" -> socks5(proxy, ClashCommon(index, server, port, name, sni, allowInsecure, network))
        else -> bad(index, ParseFailureReason.UnknownScheme, FailureDetail.Unsupported(DetailField.Scheme))
    }
}

/**
 * The fields every proxy type reads the same way.
 *
 * A carrier rather than seven parameters repeated per protocol: detekt's
 * LongParameterList is a real signal here, and a suppression on each of the
 * three would hide it everywhere.
 */
private data class ClashCommon(
    val index: Int,
    val server: String,
    val port: Int,
    val name: String,
    val sni: String,
    val allowInsecure: Boolean,
    val network: String,
)

@Suppress("ReturnCount")
private fun vmess(
    proxy: YamlMap,
    common: ClashCommon,
): LinkResult {
    val index = common.index
    val uuid =
        proxy.text("uuid")
            ?: return bad(
                index,
                ParseFailureReason.MissingCredential,
                FailureDetail.Missing(DetailField.Uuid),
            )
    validateUuid(uuid)?.let { return bad(index, ParseFailureReason.MissingCredential, it) }

    // Task 8's ruling, restated for this container: a present but unreadable
    // alterId is silent data loss. Absent means 0, malformed means say so.
    val alterIdNode = proxy.node("alterId")
    val alterId =
        if (alterIdNode == null || alterIdNode is YamlNull) {
            0
        } else {
            (alterIdNode as? YamlScalar)?.content?.toIntOrNull()
                ?: return bad(
                    index,
                    ParseFailureReason.MalformedYaml,
                    FailureDetail.Malformed(DetailField.AlterId),
                )
        }

    val security = if (proxy.text("tls") == "true") tls(common) else Security.None
    val stream = StreamSettings(network = common.network, security = security)
    val cipher = proxy.text("cipher") ?: "auto"
    val outbound = VmessOutbound(common.server, common.port, uuid, alterId, cipher, stream)
    val id = profileId("vmess", common.server, common.port, uuid)
    return LinkResult.Ok(Profile(id, common.name, outbound))
}

private fun trojan(
    proxy: YamlMap,
    common: ClashCommon,
): LinkResult {
    val password =
        proxy.text("password")
            ?: return bad(
                common.index,
                ParseFailureReason.MissingCredential,
                FailureDetail.Missing(DetailField.Password),
            )

    // Trojan is TLS-only by definition — see TrojanLink.kt.
    val stream = StreamSettings(network = common.network, security = tls(common))
    val outbound = TrojanOutbound(common.server, common.port, password, stream)
    val id = profileId("trojan", common.server, common.port, password)
    return LinkResult.Ok(Profile(id, common.name, outbound))
}

@Suppress("ReturnCount")
private fun vless(
    proxy: YamlMap,
    common: ClashCommon,
): LinkResult {
    val uuid =
        proxy.text("uuid")
            ?: return bad(common.index, ParseFailureReason.MissingCredential, FailureDetail.Missing(DetailField.Uuid))
    validateUuid(uuid)?.let { return bad(common.index, ParseFailureReason.MissingCredential, it) }

    val reality = proxy.node("reality-opts") as? YamlMap
    val security =
        if (reality != null) {
            val publicKey = reality.text("public-key").orEmpty()
            validateRealityPublicKey(publicKey)?.let {
                return bad(common.index, ParseFailureReason.InvalidRealityKey, it)
            }
            Security.Reality(
                serverName = common.sni,
                publicKey = publicKey,
                shortId = reality.text("short-id").orEmpty(),
                fingerprint = proxy.text("client-fingerprint") ?: "chrome",
                spiderX = "",
            )
        } else {
            tls(common)
        }

    val stream =
        StreamSettings(
            network = common.network,
            security = security,
            transport = transportOptions(proxy, common.network),
        )
    val outbound = VlessOutbound(common.server, common.port, uuid, proxy.text("flow"), stream)
    val id = profileId("vless", common.server, common.port, uuid)
    return LinkResult.Ok(Profile(id, common.name, outbound))
}

/**
 * SOCKS has no TLS story and no transport surface — no `stream`, unlike every
 * other branch here. The credential material mirrors `SocksLink.kt`'s
 * `credentialMaterial(username, password)` exactly, so a server imported both
 * as a `socks://` link and as a Clash `socks5` entry lands on one profile.
 */
private fun socks5(
    proxy: YamlMap,
    common: ClashCommon,
): LinkResult {
    val username = proxy.text("username")
    val password = proxy.text("password")
    val outbound = SocksOutbound(common.server, common.port, username, password)
    val id = profileId("socks", common.server, common.port, credentialMaterial(username, password))
    return LinkResult.Ok(Profile(id, common.name, outbound))
}

@Suppress("ReturnCount")
private fun shadowsocks(
    proxy: YamlMap,
    index: Int,
    server: String,
    port: Int,
    name: String,
): LinkResult {
    // Clash calls it `cipher`; Shadowsocks calls it `method`. Same field.
    val method = proxy.text("cipher").orEmpty()
    validateShadowsocksMethod(method)?.let { return bad(index, ParseFailureReason.UnsupportedMethod, it) }
    val password =
        proxy.text("password")
            ?: return bad(index, ParseFailureReason.MissingCredential, FailureDetail.Missing(DetailField.Password))

    val outbound = ShadowsocksOutbound(server, port, method, password)
    // Same identity material as ShadowsocksLink, so one server imported from a
    // Clash file and from an ss:// link is one profile, not two.
    val id = profileId("ss", server, port, shadowsocksIdentityMaterial(method, password))
    return LinkResult.Ok(Profile(id, name, outbound))
}

private fun tls(common: ClashCommon): Security.Tls =
    Security.Tls(
        serverName = common.sni,
        fingerprint = "chrome",
        allowInsecure = common.allowInsecure,
    )

private fun bad(
    index: Int,
    reason: ParseFailureReason,
    detail: FailureDetail,
): LinkResult = LinkResult.Bad(parseFailure(index, reason, detail))
