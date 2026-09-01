// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser.routing

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.DomainStrategy
import space.getsub.core.model.RouteOutcome
import java.util.Base64

class RoutingProfileImportTest {
    @Test
    fun parsesASanitizedRussiaInsideShapeFromADeeplink() {
        val result = parseFixture("russia-inside.json").shouldBeInstanceOf<ImportResult.Imported>()

        result.verb shouldBe RoutingVerb.Add
        result.profile.entryCount shouldBe 15
        result.profile.globalProxy shouldBe false
        result.profile.routeOrder shouldBe listOf(RouteOutcome.BLOCK, RouteOutcome.DIRECT, RouteOutcome.PROXY)
        result.profile.useChunkFiles shouldBe true
        result.profile.hasDns shouldBe true
    }

    @Test
    fun parsesARealBooleanGlobalProxyFromTheBlockedOnlyShape() {
        val result = parseFixture("blocked-only.json").shouldBeInstanceOf<ImportResult.Imported>()

        result.profile.globalProxy shouldBe false
        result.profile.entryCount shouldBe 3
        result.profile.routeOrder shouldBe listOf(RouteOutcome.PROXY, RouteOutcome.DIRECT, RouteOutcome.BLOCK)
    }

    @Test
    fun acceptsAllFourBase64Variants() {
        val json = """{"Name":"P","ProxySites":["example.test"],"padding":"😀"}"""
        val raw = json.toByteArray()
        val variants =
            listOf(
                Base64.getEncoder().encodeToString(raw),
                Base64.getEncoder().withoutPadding().encodeToString(raw),
                Base64.getUrlEncoder().encodeToString(raw),
                Base64.getUrlEncoder().withoutPadding().encodeToString(raw),
            )

        variants.forEach { encoded ->
            RoutingProfileImport
                .parse("happ://routing/add/$encoded")
                .shouldBeInstanceOf<ImportResult.Imported>()
                .profile.name shouldBe "P"
        }
    }

    @Test
    fun readsStringAndRealBooleans() {
        fun globalProxyOf(literal: String): Boolean? =
            RoutingProfileImport
                .parse(linkFor("""{"Name":"P","GlobalProxy":$literal,"ProxySites":["a.test"]}"""))
                .shouldBeInstanceOf<ImportResult.Imported>()
                .profile.globalProxy

        globalProxyOf("\"true\"") shouldBe true
        globalProxyOf("\"false\"") shouldBe false
        globalProxyOf("true") shouldBe true
        globalProxyOf("false") shouldBe false
    }

    @Test
    fun recognisesEveryVerbIncludingOff() {
        val payload = b64("""{"Name":"P","ProxySites":["a.test"]}""")

        RoutingProfileImport
            .parse("happ://routing/add/$payload")
            .shouldBeInstanceOf<ImportResult.Imported>()
            .verb shouldBe RoutingVerb.Add
        RoutingProfileImport
            .parse("happ://routing/onadd/$payload")
            .shouldBeInstanceOf<ImportResult.Imported>()
            .verb shouldBe RoutingVerb.OnAdd
        RoutingProfileImport.parse("happ://routing/off") shouldBe ImportResult.DisableRouting
        RoutingProfileImport.parse("subspace://routing/off") shouldBe ImportResult.DisableRouting
    }

    @Test
    fun neverThrowsOnMalformedInput() {
        val hostile =
            listOf(
                "",
                "   ",
                "not a link at all",
                "https://example.test/",
                "happ://routing/",
                "happ://routing/sideload/aGk=",
                "happ://routing/add/",
                "happ://routing/add/!!!not-base64!!!",
                linkFor("{not json"),
                linkFor("[]"),
                linkFor("null"),
                linkFor("{}"),
            )

        hostile.forEach { input ->
            RoutingProfileImport.parse(input).shouldBeInstanceOf<ImportResult>()
        }
    }

    @Test
    fun namesEverySpecificProblem() {
        fun problemOf(text: String): ImportProblem {
            val result = RoutingProfileImport.parse(text)
            return result.shouldBeInstanceOf<ImportResult.Invalid>().problem
        }

        problemOf("https://example.test/") shouldBe ImportProblem.NotARoutingLink
        problemOf("happ://routing/sideload/aGk=") shouldBe ImportProblem.UnknownVerb
        problemOf("happ://routing/add/!!!") shouldBe ImportProblem.MalformedBase64
        problemOf(linkFor("{not json")) shouldBe ImportProblem.MalformedJson
        problemOf(linkFor("{}")) shouldBe ImportProblem.MissingName
        problemOf(linkFor("""{"Name":"${"P".repeat(65)}"}""")) shouldBe ImportProblem.NameTooLong
        problemOf(linkFor("""{"Name":"P","ProxySites":["has a space"]}""")) shouldBe ImportProblem.InvalidEntry
        problemOf(linkFor("""{"Name":"P","ProxySites":["a.test"],"RouteOrder":"block-proxy"}""")) shouldBe
            ImportProblem.InvalidRouteOrder
        problemOf(linkFor("""{"Name":"P","ProxySites":["a.test"],"Geoipurl":"http://geo.test/geoip.dat"}""")) shouldBe
            ImportProblem.InsecureGeoUrl
        problemOf(
            linkFor("""{"Name":"P","ProxySites":["a.test"],"Geoipurl":"https://u:p@geo.test/geoip.dat"}"""),
        ) shouldBe
            ImportProblem.MalformedGeoUrl
        problemOf(linkFor(oversizedProfile())) shouldBe ImportProblem.TooLarge
    }

    @Test
    fun rejectsLargePayloadsBeforeNormalisingThem() {
        val largePayload = "A".repeat(MAX_PROFILE_BYTES * 2)
        val whitespaceHeavyPayload = (" \t\n".repeat(MAX_PROFILE_BYTES)) + "e30="

        RoutingProfileImport
            .parse("happ://routing/add/$largePayload")
            .shouldBeInstanceOf<ImportResult.Invalid>()
            .problem shouldBe ImportProblem.TooLarge
        RoutingProfileImport
            .parse("happ://routing/add/$whitespaceHeavyPayload")
            .shouldBeInstanceOf<ImportResult.Invalid>()
            .problem shouldBe ImportProblem.TooLarge
    }

    @Test
    fun rejectsOversizedTrailingWhitespaceAfterValidBase64() {
        val payload = b64("""{"Name":"P","ProxySites":["a.test"]}""")
        val trailingWhitespace = " ".repeat(MAX_PROFILE_BYTES * 2)

        RoutingProfileImport
            .parse("happ://routing/add/$payload$trailingWhitespace")
            .shouldBeInstanceOf<ImportResult.Invalid>()
            .problem shouldBe ImportProblem.TooLarge
    }

    @Test
    fun rejectsMalformedUtf8BeforeParsingJson() {
        val bytes =
            "{\"Name\":\"".toByteArray() +
                byteArrayOf(0xC3.toByte(), 0x28) +
                "\",\"ProxySites\":[\"a.test\"]}".toByteArray()

        RoutingProfileImport
            .parse(linkFor(bytes))
            .shouldBeInstanceOf<ImportResult.Invalid>()
            .problem shouldBe ImportProblem.MalformedJson
    }

    @Test
    fun requiresAStringName() {
        listOf("null", "1", "true").forEach { nonStringName ->
            RoutingProfileImport
                .parse(linkFor("""{"Name":$nonStringName,"ProxySites":["a.test"]}"""))
                .shouldBeInstanceOf<ImportResult.Invalid>()
                .problem shouldBe ImportProblem.MissingName
        }
    }

    @Test
    fun defaultsAbsentRouteOrderAndUnknownDomainStrategy() {
        val result =
            RoutingProfileImport
                .parse(
                    linkFor("""{"Name":"P","ProxySites":["a.test"],"DomainStrategy":"Whatever"}"""),
                ).shouldBeInstanceOf<ImportResult.Imported>()

        result.profile.routeOrder shouldBe listOf(RouteOutcome.BLOCK, RouteOutcome.PROXY, RouteOutcome.DIRECT)
        result.profile.domainStrategy shouldBe DomainStrategy.IP_IF_NON_MATCH
    }

    @Test
    fun capturesTheDnsBlockAsATypedProfileDns() {
        val json =
            """
            {"Name":"P","ProxySites":["a.test"],
             "RemoteDNSType":"DoH","RemoteDNSDomain":"https://dns.test/dns-query",
             "DomesticDNSType":"DoU","DomesticDNSIP":"203.0.113.5",
             "DnsHosts":{"dns.test":"203.0.113.6"},"FakeDNS":"true"}
            """.trimIndent()
        val profile =
            RoutingProfileImport
                .parse(
                    linkFor(json),
                ).shouldBeInstanceOf<ImportResult.Imported>()
                .profile

        profile.hasDns shouldBe true
        val dns = checkNotNull(profile.dns)
        dns.isInvalid shouldBe false
        dns.remote?.transport shouldBe DnsTransport.DOH
        dns.domestic?.transport shouldBe DnsTransport.DOU
        dns.fakeDns shouldBe true
    }

    @Test
    fun readsLastUpdatedAsUnixSecondsAndToleratesJunk() {
        fun lastUpdatedOf(literal: String): Long? =
            RoutingProfileImport
                .parse(
                    linkFor("""{"Name":"P","ProxySites":["a.test"],"LastUpdated":$literal}"""),
                ).shouldBeInstanceOf<ImportResult.Imported>()
                .profile.lastUpdated

        lastUpdatedOf("\"1700000000\"") shouldBe 1_700_000_000L
        lastUpdatedOf("1700000000") shouldBe 1_700_000_000L
        lastUpdatedOf("\"\"") shouldBe null
        lastUpdatedOf("\"not a date\"") shouldBe null
    }

    private fun parseFixture(name: String): ImportResult = RoutingProfileImport.parse(linkFor(fixture(name)))

    private fun linkFor(json: String): String = "happ://routing/add/${b64(json)}"

    private fun b64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun linkFor(bytes: ByteArray): String = "happ://routing/add/${Base64.getEncoder().encodeToString(bytes)}"

    private fun oversizedProfile(): String = """{"Name":"P","Padding":"${"x".repeat(MAX_PROFILE_BYTES + 1)}"}"""

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("routing/$name")) {
            "missing fixture routing/$name"
        }.reader().readText()
}
