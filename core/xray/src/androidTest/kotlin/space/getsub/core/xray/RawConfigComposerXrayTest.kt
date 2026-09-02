// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RoutingRuleSet
import space.getsub.core.model.RuleBucket
import space.getsub.core.parser.OverrideTarget
import space.getsub.core.parser.analysePassthrough
import space.getsub.core.parser.resolveOverrideTarget
import java.io.File

/**
 * §10.1 in miniature: the unit tests prove the composer writes the JSON we
 * intended, and prove nothing about whether xray-core will run it.
 */
@RunWith(AndroidJUnit4::class)
class RawConfigComposerXrayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val settings =
        TunnelSettings(socksPort = 41080, dnsServer = "1.1.1.1", enableSniffing = true, httpPort = 41081)

    // The target panel's balancer template, reduced to its structure. Addresses
    // and credentials are placeholders — §5.6, and the core does not dial during
    // validation, so they need only parse.
    private val balancerConfig =
        """
        {
          "burstObservatory": {
            "pingConfig": { "timeout": "3s", "interval": "2m", "sampling": 3,
                            "destination": "http://www.gstatic.com/generate_204", "connectivity": "" },
            "subjectSelector": ["proxy"]
          },
          "dns": { "servers": ["8.8.8.8"] },
          "log": { "loglevel": "info" },
          "routing": {
            "rules": [
              { "ip": ["8.8.8.8"], "port": "53", "type": "field", "balancerTag": "Auto_Balancer" },
              { "type": "field", "network": "tcp,udp", "balancerTag": "Auto_Balancer" }
            ],
            "balancers": [
              { "tag": "Auto_Balancer", "selector": ["proxy"],
                "strategy": { "type": "leastLoad",
                              "settings": { "maxRTT": "1500ms", "expected": 3,
                                            "baselines": ["200ms", "500ms"], "tolerance": 50 } },
                "fallbackTag": "FALLBACK_TAG" }
            ],
            "domainStrategy": "AsIs"
          },
          "inbounds": [
            { "tag": "socks", "port": 10808, "listen": "127.0.0.1", "protocol": "socks",
              "sniffing": { "enabled": true, "routeOnly": false, "destOverride": ["http", "tls"] } }
          ],
          "outbounds": [
            { "tag": "proxy-auto", "protocol": "vless",
              "settings": { "vnext": [ { "address": "192.0.2.1", "port": 443,
                "users": [ { "id": "00000000-0000-0000-0000-000000000001", "encryption": "none" } ] } ] },
              "streamSettings": { "network": "tcp", "security": "none" } },
            { "tag": "proxy-auto-2", "protocol": "vless",
              "settings": { "vnext": [ { "address": "192.0.2.2", "port": 443,
                "users": [ { "id": "00000000-0000-0000-0000-000000000002", "encryption": "none" } ] } ] },
              "streamSettings": { "network": "tcp", "security": "none" } },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ]
        }
        """.trimIndent()

    private suspend fun validate(raw: String, override: OverrideBlocks? = null): Result<Unit> {
        val assetDir = File(context.filesDir, "geo").apply { mkdirs() }
        val controller = XrayController(geoAssetDir = assetDir)
        val result = RawConfigComposer.compose(raw, settings, assetDir.absolutePath, override)
        check(result is ComposeResult.Ok) { "composer refused the fixture: $result" }
        val file = File(context.cacheDir, "passthrough-test.json").apply { writeText(result.json) }
        return runCatching { controller.validate(file) }
    }

    /**
     * Every `tag` on the composed config's top-level `outbounds`, in array order and with
     * duplicates kept (a `List`, not a `Set`) — the whole point is to be able to tell a config
     * with one `direct` outbound from a config with two.
     *
     * §10.5 correction pass: the guard this replaces, `"\"tag\": \"proxy\"" !in composed.json`,
     * searched for a space after the colon that `RawConfigComposer.render`'s compact
     * `JsonElement.toString()` output never has, so it could never fire — parsing and reading the
     * tags directly is the fix the review asked for.
     */
    private fun outboundTagsOf(composedJson: String): List<String> =
        (Json.parseToJsonElement(composedJson).jsonObject["outbounds"] as JsonArray)
            .filterIsInstance<JsonObject>()
            .mapNotNull { (it["tag"] as? JsonPrimitive)?.content }

    /**
     * Mirrors `TunnelService.composePassthrough`'s dedup filter (`existingOutboundTags`/
     * `String.tagOf()`, `service/src/main/kotlin/.../TunnelService.kt`) by hand: `:core:xray`
     * cannot depend on `:service` (§4), so this is the same rule restated rather than imported —
     * drop any of [override]'s stock `direct`/`block`/`dns-out` outbounds whose tag the raw
     * config's own `outbounds` already carries, exactly as the real passthrough path does before
     * composing. Only this filtered shape is what a device ever actually sends to the core; an
     * unfiltered [OverrideBlocks] (below) is a shape `composePassthrough` never produces.
     */
    private fun filteredForProductionShape(
        rawJson: String,
        override: OverrideBlocks,
    ): OverrideBlocks {
        val existingTags =
            (Json.parseToJsonElement(rawJson).jsonObject["outbounds"] as JsonArray)
                .filterIsInstance<JsonObject>()
                .mapNotNull { (it["tag"] as? JsonPrimitive)?.content }
                .toSet()
        fun String.tagOf(): String? =
            (Json.parseToJsonElement(this) as? JsonObject)
                ?.get("tag")
                ?.let { (it as? JsonPrimitive)?.content }
        return override.copy(
            extraOutboundsJson = override.extraOutboundsJson.filter { it.tagOf() !in existingTags },
        )
    }

    // A config whose routing needs a geo asset — the shape every config the
    // target panel emits has, and the shape no other fixture in this file has.
    private val geoRuleConfig =
        """
        {
          "dns": { "servers": ["8.8.8.8"] },
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [ { "type": "field", "domain": ["geosite:category-ru"], "outboundTag": "direct" } ]
          },
          "outbounds": [
            { "tag": "proxy", "protocol": "vless",
              "settings": { "vnext": [ { "address": "192.0.2.1", "port": 443,
                "users": [ { "id": "00000000-0000-0000-0000-000000000001", "encryption": "none" } ] } ] },
              "streamSettings": { "network": "tcp", "security": "none" } },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ]
        }
        """.trimIndent()

    /**
     * §10.5, measured 2026-08-31 on a Pixel 8. The composed config's own
     * `env["xray.location.asset"]` is the only channel `testXray` resolves geo
     * files from; `XrayController`'s `geoAssetDir` envelope does not override
     * it. Asserted by pointing the two at *different* directories and reading
     * which one the core's refusal names.
     *
     * Deliberately asserts the negative — a rejection naming the composed
     * directory — rather than a success: a positive case would need a real
     * multi-megabyte `geosite.dat` committed as a fixture, and the direction
     * that matters here is which path the core went looking down.
     */
    @Test
    fun theComposedEnvNotTheInvokeEnvelopeIsWhereTheCoreResolvesGeoFiles() =
        runTest {
            val fromJson =
                File(context.filesDir, "geo-json").apply {
                    deleteRecursively()
                    mkdirs()
                }
            val fromEnvelope =
                File(context.filesDir, "geo-envelope").apply {
                    deleteRecursively()
                    mkdirs()
                }

            val composed = RawConfigComposer.compose(geoRuleConfig, settings, fromJson.absolutePath, null)
            check(composed is ComposeResult.Ok)
            val file = File.createTempFile("geo-channel", ".json", context.cacheDir)
            val result =
                try {
                    file.writeText(composed.json)
                    runCatching { XrayController(geoAssetDir = fromEnvelope).validate(file) }
                } finally {
                    file.delete()
                    // Cleaned here, not only at the next run's setup, so this test leaves no
                    // trace in the app's files dir regardless of how it exits — matching the
                    // temp config file above rather than leaving an asymmetry.
                    fromJson.deleteRecursively()
                    fromEnvelope.deleteRecursively()
                }

            val message = result.exceptionOrNull()?.message.orEmpty()
            // §5.6: XrayException's message can quote the config back (LibXrayInvoke's own
            // KDoc), so only the exception's class name is safe to surface in a failure
            // message — never `.exceptionOrNull()`. `message` above is read only for the
            // assertions themselves; the failure text below names the two directories (this
            // test's own, not config-derived) and a boolean instead of the message itself.
            check("failed to open geosite.dat" in message) {
                "expected a geo-asset refusal; got a ${result.exceptionOrNull()?.javaClass?.simpleName}"
            }
            check(fromJson.absolutePath in message) {
                "the core looked somewhere other than the composed env " +
                    "(composedDir=${fromJson.name}, envelopeDir=${fromEnvelope.name}, " +
                    "sawComposedDir=${fromJson.absolutePath in message})"
            }
            check(fromEnvelope.absolutePath !in message) {
                "the core read the invoke envelope after all " +
                    "(composedDir=${fromJson.name}, envelopeDir=${fromEnvelope.name}, " +
                    "sawEnvelopeDir=${fromEnvelope.absolutePath in message})"
            }
        }

    @Test
    fun theCoreAcceptsAComposedBalancerConfig() =
        runTest {
            val outcome = validate(balancerConfig.replace("FALLBACK_TAG", "proxy-auto"))

            // §5.6: XrayException's message can quote the config back
            // (LibXrayInvoke's own KDoc), so only the exception's class name is
            // safe to surface in a failure message — never `.exceptionOrNull()`.
            check(outcome.isSuccess) {
                "core rejected a well-formed balancer config: ${outcome.exceptionOrNull()?.javaClass?.simpleName}"
            }
        }

    /**
     * Spec §6.2's one unresolved fact, and checklist row 9c.
     *
     * The target panel's template names `fallbackTag: "proxy"` while its
     * outbounds are `proxy-auto`, `proxy-auto-2`, … — `fallbackTag` is an exact
     * tag, not a prefix (research §3.2), so it points at nothing.
     *
     * **Observed 2026-08-25, Pixel 8, `connectedDebugAndroidTest`:** xray-core
     * accepts this at config build — `testXray` succeeds. `validate()` never
     * dials or runs traffic (see the fixture comment above and
     * `XrayController.validate`'s KDoc), so this test shows only that one bit:
     * accepted-at-build, not rejected-at-build. What happens if and when the
     * balancer's fallback actually fires was not exercised here and remains
     * open. Recorded in
     * `docs/agent/research/2026-08-25-remnawave-xray-json-and-balancers.md` §6.
     */
    @Test
    fun theCoreAcceptsAConfigWithADanglingFallbackTagAtBuild() =
        runTest {
            val outcome = validate(balancerConfig)

            check(outcome.isSuccess) {
                val exceptionName = outcome.exceptionOrNull()?.javaClass?.simpleName
                "expected the core to accept a dangling fallbackTag at config build " +
                    "(observed 2026-08-25 on Pixel 8) but it was rejected: $exceptionName"
            }
        }

    /**
     * A [RoutingRuleSet] whose only bucket is PROXY, so
     * `XrayConfigGenerator.overrideBlocks(routingSettings).routingJson` carries a rule naming
     * `outboundTag: "proxy"` — the tag [balancerConfig] itself never defines (its own outbounds are
     * `proxy-auto`/`proxy-auto-2`, plus `direct`/`block`).
     */
    private val routingSettings =
        settings.copy(
            routing =
            RoutingRuleSet(
                name = "device-test",
                buckets = mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf("example.com"))),
            ),
        )

    /** [balancerConfig] with an extra outbound tagged `"proxy"`, prepended to its own list. */
    private val balancerConfigWithProxyOutbound =
        balancerConfig.replaceFirst(
            "\"outbounds\": [",
            "\"outbounds\": [\n            { \"tag\": \"proxy\", \"protocol\": \"freedom\" },",
        )

    /**
     * Final review I9, then a correction pass on it (§10.5).
     *
     * Both cases above compose with `override = null` — the pure passthrough branch. This exercises
     * the override branch, which splices the app's own routing/dns/extra outbounds into someone
     * else's config, for the first time.
     *
     * **This test does NOT answer, on its own, whether xray-core rejects a dangling
     * `outboundTag`.** The original version of this test claimed it did; that claim did not survive
     * re-review, and the composed config it hands the core has *two* defects at once, not one:
     *
     * 1. The dangling reference itself — [routingSettings]'s override rule names `outboundTag:
     *    "proxy"`, which [balancerConfig] never defines.
     * 2. `XrayConfigGenerator.overrideBlocks` unconditionally emits stock `direct`/`block`
     *    outbounds, and [RawConfigComposer.compose] appends them unconditionally
     *    (`RawConfigComposer.kt`) — but [balancerConfig] already defines its own `direct` and
     *    `block`. The composed config this test used to hand the core therefore also carries
     *    **duplicate `direct` and `block` tags**, which `TunnelService.composePassthrough`'s dedup
     *    filter exists specifically to prevent in production (`existingOutboundTags`/`tagOf()`,
     *    `TunnelService.kt`) — this test never applied that filter, so it never composed the shape
     *    a device actually sends.
     *
     * A core rejection here is consistent with either defect, or both — it cannot isolate which one
     * the core is reacting to. [theCoreAcceptsAFilteredOverrideWithOnlyADanglingProxyOutboundTag]
     * and [theCoreRejectsAnUnfilteredOverrideWithOnlyDuplicateDirectAndBlockOutboundTags] below
     * compose each defect alone; **those two, not this one, are the source for what
     * ARCHITECTURE.md and the research doc now say.** The isolated pair settled it: the rejection
     * observed here is driven entirely by the duplicate `direct`/`block` tags — a config with the
     * dangling `outboundTag` alone and no duplicates is *accepted* at config build.
     *
     * Kept as a regression guard on the unfiltered/naive shape specifically (a shape
     * `composePassthrough` itself never produces, since it always filters first) — not as an answer
     * to the dangling-tag question.
     */
    @Test
    fun theCoreRejectsTheUnfilteredOverrideBranchWithBothDanglingTagAndDuplicateOutbounds() =
        runTest {
            val override = XrayConfigGenerator.overrideBlocks(routingSettings, OverrideTarget.ViaOutbound("proxy"))

            // Confirm the fixture actually poses both defects at once before asking the core.
            val composed = RawConfigComposer.compose(balancerConfig, routingSettings, "/data/geo", override)
            check(composed is ComposeResult.Ok) { "composer refused the fixture: $composed" }
            val tags = outboundTagsOf(composed.json)
            check("proxy" !in tags) {
                "fixture defines a \"proxy\" outbound after all — no longer exercises the dangling tag: $tags"
            }
            check(tags.count { it == "direct" } == 2 && tags.count { it == "block" } == 2) {
                "fixture no longer carries duplicate direct/block tags — no longer exercises that defect: $tags"
            }
            check("\"outboundTag\": \"proxy\"" in override.routingJson) {
                "override routing did not reference outboundTag \"proxy\" — fixture is not testing what it claims"
            }

            val outcome = validate(balancerConfig, override)

            check(outcome.isFailure) {
                "expected the core to reject this two-defect config " +
                    "(observed 2026-08-30 on Pixel 8) but it was accepted"
            }
            // §5.6: only the exception's class name is safe to surface (see the other tests'
            // comment above `theCoreAcceptsAComposedBalancerConfig`).
            check(outcome.exceptionOrNull() is XrayException) {
                "core rejected the config for an unexpected reason: " +
                    "${outcome.exceptionOrNull()?.javaClass?.simpleName}"
            }
        }

    /**
     * Isolates the dangling-`outboundTag` defect alone, by composing the shape
     * `TunnelService.composePassthrough` actually produces in production: the override's stock
     * `direct`/`block` outbounds filtered against [balancerConfig]'s own tags first
     * ([filteredForProductionShape], mirroring `existingOutboundTags`/`tagOf()`), so neither
     * survives the append — [balancerConfig] already carries both. The composed config's only
     * remaining defect is the routing rule's `outboundTag: "proxy"`, which nothing in the composed
     * `outbounds` defines.
     *
     * **Observed 2026-08-30, Pixel 8, `connectedDebugAndroidTest`:** xray-core **ACCEPTS** this at
     * config build — `testXray` succeeds. This is the opposite of what the confounded original
     * version of this test concluded, and it flips the answer to ARCHITECTURE.md §6 and the
     * research doc's Question 3: a dangling `outboundTag` alone does *not* fail visibly at
     * `testXray`. [theCoreRejectsTheUnfilteredOverrideBranchWithBothDanglingTagAndDuplicateOutbounds]'s
     * rejection was driven entirely by the duplicate-tag defect below, not by this one — confirmed
     * by [theCoreRejectsAnUnfilteredOverrideWithOnlyDuplicateDirectAndBlockOutboundTags], which
     * reproduces the rejection with the dangling reference removed and only duplicates left. The
     * `overrideBlocker` gap this was meant to settle (`ARCHITECTURE.md` §6, `NoProxyTag`/
     * `AmbiguousOutboundTags`) is therefore real, not cosmetic, for this specific failure mode: a
     * config missing a `proxy` tag can pass both import-time and connect-time `testXray` and then
     * simply never match the rule that names it — exactly the silent-no-match risk the "go one step
     * further" half of Question 3's procedure describes, and which this test does not itself run
     * (it stops at config build, per its own name).
     */
    @Test
    fun theCoreAcceptsAFilteredOverrideWithOnlyADanglingProxyOutboundTag() =
        runTest {
            val override =
                filteredForProductionShape(
                    balancerConfig,
                    XrayConfigGenerator.overrideBlocks(routingSettings, OverrideTarget.ViaOutbound("proxy")),
                )

            val composed = RawConfigComposer.compose(balancerConfig, routingSettings, "/data/geo", override)
            check(composed is ComposeResult.Ok) { "composer refused the fixture: $composed" }
            val tags = outboundTagsOf(composed.json)
            val expectedTags = setOf("proxy-auto", "proxy-auto-2", "direct", "block")
            check(tags.toSet() == expectedTags && tags.size == tags.toSet().size) {
                "fixture does not isolate the dangling-tag defect alone — composed tags: $tags"
            }
            check("\"outboundTag\": \"proxy\"" in override.routingJson) {
                "override routing did not reference outboundTag \"proxy\" — fixture is not testing what it claims"
            }

            val outcome = validate(balancerConfig, override)

            check(outcome.isSuccess) {
                val exceptionName = outcome.exceptionOrNull()?.javaClass?.simpleName
                "expected the core to accept a dangling outboundTag alone, isolated from the " +
                    "duplicate-tag defect (observed 2026-08-30 on Pixel 8) but it was rejected: $exceptionName"
            }
        }

    /**
     * Isolates the duplicate-outbound-tag defect alone, as a control: [balancerConfigWithProxyOutbound]
     * defines its own `"proxy"` outbound, so the override routing rule naming `outboundTag: "proxy"`
     * resolves — no dangling reference. The override is left **unfiltered**
     * (`XrayConfigGenerator.overrideBlocks` as-is), so its stock `direct`/`block` outbounds append
     * on top of [balancerConfigWithProxyOutbound]'s own `direct`/`block`, producing duplicate tags —
     * the one remaining defect. Between this test and
     * [theCoreAcceptsAFilteredOverrideWithOnlyADanglingProxyOutboundTag] above, each defect the
     * confounded original test conflated is now isolated on its own.
     *
     * **Observed 2026-08-30, Pixel 8, `connectedDebugAndroidTest`:** xray-core **REJECTS** this at
     * config build — `testXray` throws `XrayException`. Combined with the acceptance observed above,
     * this settles it: the duplicate `direct`/`block` tags, not the dangling `outboundTag`, are what
     * the core actually objects to.
     */
    @Test
    fun theCoreRejectsAnUnfilteredOverrideWithOnlyDuplicateDirectAndBlockOutboundTags() =
        runTest {
            val override = XrayConfigGenerator.overrideBlocks(routingSettings, OverrideTarget.ViaOutbound("proxy"))

            val composed =
                RawConfigComposer.compose(balancerConfigWithProxyOutbound, routingSettings, "/data/geo", override)
            check(composed is ComposeResult.Ok) { "composer refused the fixture: $composed" }
            val tags = outboundTagsOf(composed.json)
            check("proxy" in tags) {
                "fixture does not define a \"proxy\" outbound — the dangling-tag defect is not resolved: $tags"
            }
            check(tags.count { it == "direct" } == 2 && tags.count { it == "block" } == 2) {
                "fixture does not carry duplicate direct/block tags — no longer exercises that defect: $tags"
            }
            check("\"outboundTag\": \"proxy\"" in override.routingJson) {
                "override routing did not reference outboundTag \"proxy\" — fixture is not testing what it claims"
            }

            val outcome = validate(balancerConfigWithProxyOutbound, override)

            check(outcome.isFailure) {
                "expected the core to reject duplicate direct/block outbound tags, in isolation from " +
                    "the dangling-tag defect (observed 2026-08-30 on Pixel 8) but it was accepted"
            }
            check(outcome.exceptionOrNull() is XrayException) {
                "core rejected the config for an unexpected reason: " +
                    "${outcome.exceptionOrNull()?.javaClass?.simpleName}"
            }
        }

    /**
     * A3, pinned. Measured first on a Pixel 8, 2026-09-01
     * (`docs/agent/research/2026-09-01-balancer-tag-binding.md`): the core
     * refuses a `balancerTag` naming a balancer the config does not declare,
     * with `app/router: balancer <tag> not found`, at config build.
     *
     * The whole M7.5 failure model rests on this being a *loud* failure, so it
     * is a standing test rather than a line in a research file. If this ever
     * starts passing, a wrong balancer resolution has become silent and the
     * design's loudness argument no longer holds.
     *
     * §5.6: the core's message can quote the config back, so the assertion
     * reduces it to a boolean and the failure text names only our own literal.
     */
    @Test
    fun theCoreRefusesARuleNamingABalancerTheConfigDoesNotDeclare() =
        runTest {
            val override =
                filteredForProductionShape(
                    balancerConfig,
                    XrayConfigGenerator.overrideBlocks(routingSettings, OverrideTarget.ViaBalancer("no-such-balancer")),
                )

            val result = validate(balancerConfig, override)

            check(result.isFailure) {
                "expected the core to refuse a dangling balancerTag at config build (A3, Pixel 8 " +
                    "2026-09-01) but it was accepted"
            }
            val namesTheMissingBalancer =
                "balancer no-such-balancer not found" in result.exceptionOrNull()?.message.orEmpty()
            check(namesTheMissingBalancer) {
                "the core refused for some other reason: " +
                    "${result.exceptionOrNull()?.javaClass?.simpleName}, namedTheBalancer=false"
            }
        }

    /**
     * The other half of A3's asymmetry, in the same rig: a dangling
     * *outboundTag* is accepted at build and fails only when the rule fires
     * (M7's finding F9). This is why `OverrideTarget.ViaOutbound` may only ever
     * name an outbound the config actually defines — there is no backstop on
     * that branch.
     */
    @Test
    fun theCoreAcceptsARuleNamingAnOutboundTheConfigDoesNotDeclare() =
        runTest {
            val override =
                filteredForProductionShape(
                    balancerConfig,
                    XrayConfigGenerator.overrideBlocks(routingSettings, OverrideTarget.ViaOutbound("no-such-outbound")),
                )

            val outcome = validate(balancerConfig, override)

            check(outcome.isSuccess) {
                "expected the core to accept a dangling outboundTag at config build (F9, A3) but it " +
                    "was rejected: ${outcome.exceptionOrNull()?.javaClass?.simpleName}"
            }
        }

    /**
     * The milestone's whole point, against the real core: the panel's balancer
     * shape composes with the app's routing on top and is accepted.
     *
     * Acceptance is not the same as routing correctly — §10.1, and the exit
     * criterion in the spec's §7 is a device run, not this test.
     */
    @Test
    fun aBalancerConfigComposesWithTheAppOverrideAndTheCoreAcceptsIt() =
        runTest {
            val target =
                resolveOverrideTarget(analysePassthrough(balancerConfig), setOf("direct", "block", "dns-out"))
            check(target == OverrideTarget.ViaBalancer("Auto_Balancer")) {
                "the resolver regressed before the core was reached"
            }
            check(target is OverrideTarget.Resolved)

            val override =
                filteredForProductionShape(
                    balancerConfig,
                    XrayConfigGenerator.overrideBlocks(routingSettings, target),
                )

            val outcome = validate(balancerConfig, override)

            check(outcome.isSuccess) {
                "the core rejected a balancer config carrying the app override: " +
                    "${outcome.exceptionOrNull()?.javaClass?.simpleName}"
            }
        }
}
