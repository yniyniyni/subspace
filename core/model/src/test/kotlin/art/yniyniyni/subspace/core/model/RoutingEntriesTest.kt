// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("Indentation") // KtLint's required continuation indentation conflicts with detekt's style rule.

package art.yniyniyni.subspace.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class RoutingEntriesTest {
    // ---- geoFileFor: which .dat an entry needs, if any ----

    @Test
    fun `geosite maps to the default geosite dat`() {
        RoutingEntries.geoFileFor("geosite:category-ads-all", BucketField.SITES) shouldBe "geosite.dat"
    }

    @Test
    fun `geoip maps to the default geoip dat`() {
        RoutingEntries.geoFileFor("geoip:ru", BucketField.IPS) shouldBe "geoip.dat"
    }

    @Test
    fun `a negated geoip still needs the file`() {
        RoutingEntries.geoFileFor("!geoip:ru", BucketField.IPS) shouldBe "geoip.dat"
    }

    @Test
    fun `ext names its own file`() {
        RoutingEntries.geoFileFor("ext:mylist.dat:ads", BucketField.SITES) shouldBe "mylist.dat"
        RoutingEntries.geoFileFor("ext-site:mylist.dat:ads", BucketField.SITES) shouldBe "mylist.dat"
        RoutingEntries.geoFileFor("ext-domain:mylist.dat:ads", BucketField.SITES) shouldBe "mylist.dat"
        RoutingEntries.geoFileFor("ext-ip:mynets.dat:home", BucketField.IPS) shouldBe "mynets.dat"
    }

    @Test
    fun `unsafe ext filenames never name a geo file`() {
        listOf(
            "ext:../outside.dat:ads",
            "ext:subdir/list.dat:ads",
            "ext:.dat:ads",
            "ext:list.txt:ads",
        ).forEach { entry ->
            RoutingEntries.geoFileFor(entry, BucketField.SITES) shouldBe null
            RoutingEntries.problemWith(entry, BucketField.SITES) shouldBe EntryProblem.MalformedExtReference
        }

        RoutingEntries.geoFileFor("ext:subdir\\list.dat:ads", BucketField.SITES) shouldBe null
        RoutingEntries.problemWith("ext:subdir\\list.dat:ads", BucketField.SITES) shouldBe
            EntryProblem.IllegalCharacter
    }

    @Test
    fun `geosite is not a geo reference in an ip bucket`() {
        // ParseIPRules never rewrites `geosite:`; it would fall through to
        // parseCustomIPRule and fail there, not load a file.
        RoutingEntries.geoFileFor("geosite:cn", BucketField.IPS) shouldBe null
    }

    @Test
    fun `literals need no file`() {
        RoutingEntries.geoFileFor("10.0.0.0/8", BucketField.IPS) shouldBe null
        RoutingEntries.geoFileFor("fc00::/7", BucketField.IPS) shouldBe null
        RoutingEntries.geoFileFor("example.com", BucketField.SITES) shouldBe null
        RoutingEntries.geoFileFor("domain:example.com", BucketField.SITES) shouldBe null
        RoutingEntries.geoFileFor("regexp:.*[.]example[.]com", BucketField.SITES) shouldBe null
    }

    // ---- requiredGeoFiles: the activation gate's input ----

    @Test
    fun `a rule set of literals requires nothing`() {
        val set =
            RoutingRuleSet(
                name = "lan",
                buckets =
                    mapOf(
                        RouteOutcome.DIRECT to
                            RuleBucket(ips = listOf("10.0.0.0/8", "192.168.0.0/16", "fc00::/7")),
                    ),
            )

        set.requiredGeoFiles() shouldBe emptySet()
    }

    @Test
    fun `a rule set collects every distinct file it references`() {
        val set =
            RoutingRuleSet(
                name = "mixed",
                buckets =
                    mapOf(
                        RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:category-ads-all")),
                        RouteOutcome.DIRECT to
                            RuleBucket(
                                sites = listOf("geosite:cn", "example.com"),
                                ips = listOf("geoip:cn", "10.0.0.0/8", "ext-ip:mynets.dat:home"),
                            ),
                    ),
            )

        set.requiredGeoFiles() shouldBe setOf("geosite.dat", "geoip.dat", "mynets.dat")
    }

    // ---- problemWith: what the editor rejects while typing ----

    @Test
    fun `accepts well-formed entries`() {
        listOf(
            "geosite:cn" to BucketField.SITES,
            "ext:mylist.dat:ads" to BucketField.SITES,
            "domain:example.com" to BucketField.SITES,
            "full:www.example.com" to BucketField.SITES,
            "keyword:example" to BucketField.SITES,
            "dotless:localhost" to BucketField.SITES,
            "regexp:^.*[.]example[.]com$" to BucketField.SITES,
            "example.com" to BucketField.SITES,
            "geoip:ru" to BucketField.IPS,
            "!geoip:ru" to BucketField.IPS,
            "10.0.0.0/8" to BucketField.IPS,
            "192.168.1.1" to BucketField.IPS,
            "fc00::/7" to BucketField.IPS,
            "::1" to BucketField.IPS,
            "::ffff:192.0.2.1" to BucketField.IPS,
            "2001:db8::1" to BucketField.IPS,
            "2001:db8:0:0:0:0:2:1" to BucketField.IPS,
        ).forEach { (entry, field) ->
            RoutingEntries.problemWith(entry, field) shouldBe null
        }
    }

    @Test
    fun `rejects blank`() {
        RoutingEntries.problemWith("   ", BucketField.SITES) shouldBe EntryProblem.Blank
    }

    @Test
    fun `rejects a geo reference with no code`() {
        RoutingEntries.problemWith("geosite:", BucketField.SITES) shouldBe EntryProblem.MissingGeoCode
        RoutingEntries.problemWith("geoip:", BucketField.IPS) shouldBe EntryProblem.MissingGeoCode
    }

    @Test
    fun `rejects a malformed ext reference`() {
        // No ":code" segment at all.
        RoutingEntries.problemWith("ext:mylist.dat", BucketField.SITES) shouldBe
            EntryProblem.MalformedExtReference
        // No filename.
        RoutingEntries.problemWith("ext::ads", BucketField.SITES) shouldBe
            EntryProblem.MalformedExtReference
    }

    @Test
    fun `rejects a malformed address in an ip bucket`() {
        RoutingEntries.problemWith("999.1.1.1", BucketField.IPS) shouldBe EntryProblem.MalformedAddress
        RoutingEntries.problemWith("10.0.0.0/33", BucketField.IPS) shouldBe EntryProblem.MalformedAddress
        RoutingEntries.problemWith("fc00::/129", BucketField.IPS) shouldBe EntryProblem.MalformedAddress
        RoutingEntries.problemWith("example.com", BucketField.IPS) shouldBe EntryProblem.MalformedAddress
    }

    /**
     * A prefix that is not a number at all, as distinct from one that is out of
     * range: `/33` parses and fails the range check, `/abc` fails to parse.
     *
     * Those are separate lines in `isAddressOrCidr`, and this is the only test
     * that reaches the parse one — which matters because that line carries an
     * `UnreachableCode` suppression whose justification is that a test proves it
     * reachable. Delete this and the suppression becomes an unchecked claim.
     */
    @Test
    fun `rejects a non-numeric cidr prefix`() {
        RoutingEntries.problemWith("10.0.0.0/abc", BucketField.IPS) shouldBe EntryProblem.MalformedAddress
        RoutingEntries.problemWith("fc00::/x", BucketField.IPS) shouldBe EntryProblem.MalformedAddress
    }

    @Test
    fun `rejects malformed ipv6 syntax`() {
        listOf(
            ":::1",
            "1:2:3:4:5:6:7",
            ":1:2:3:4:5:6:7:8",
            "1:2:3:4:5:6:7:8:",
            "::ffff:999.0.2.1",
        ).forEach { entry ->
            RoutingEntries.problemWith(entry, BucketField.IPS) shouldBe EntryProblem.MalformedAddress
        }
    }

    @Test
    fun `only one leading ip negation is valid`() {
        RoutingEntries.problemWith("!!geoip:ru", BucketField.IPS) shouldBe EntryProblem.MalformedAddress
        RoutingEntries.geoFileFor("!!geoip:ru", BucketField.IPS) shouldBe null
    }

    @Test
    fun `rejects a malformed domain in a sites bucket`() {
        RoutingEntries.problemWith("has space.com", BucketField.SITES) shouldBe EntryProblem.MalformedDomain
        RoutingEntries.problemWith("https://example.com", BucketField.SITES) shouldBe EntryProblem.MalformedDomain
        RoutingEntries.problemWith("example.com/path", BucketField.SITES) shouldBe EntryProblem.MalformedDomain
    }

    // The generator writes JSON by hand (§6). An entry carrying a quote or a
    // backslash would inject arbitrary JSON into the config; this is the guard.
    @Test
    fun `rejects json-breaking characters in every field`() {
        RoutingEntries.problemWith("""ex"ample.com""", BucketField.SITES) shouldBe
            EntryProblem.IllegalCharacter
        RoutingEntries.problemWith("""ex\ample.com""", BucketField.SITES) shouldBe
            EntryProblem.IllegalCharacter
        RoutingEntries.problemWith("""geoip:r"u""", BucketField.IPS) shouldBe
            EntryProblem.IllegalCharacter
    }

    @Test
    fun `rejects every raw json control character in every field`() {
        (0..0x1F).forEach { code ->
            val entry = "safe${code.toChar()}value"
            BucketField.entries.forEach { field ->
                RoutingEntries.problemWith(entry, field) shouldBe EntryProblem.IllegalCharacter
            }
        }
    }

    // Go's regexp is not Java's. Validating with java.util.regex would reject
    // valid Go expressions, which is a wrong answer delivered confidently.
    @Test
    fun `accepts a regexp body without compiling it`() {
        RoutingEntries.problemWith("regexp:(?P<name>a)+", BucketField.SITES) shouldBe null
    }

    @Test
    fun `rejects an empty regexp body`() {
        RoutingEntries.problemWith("regexp:", BucketField.SITES) shouldBe EntryProblem.Blank
    }

    // ---- builtInPrefix / builtInGeoFileName: the shared authority fix round 1 review asked
    // :feature:routing's rule set editor to derive from, instead of re-declaring the literals ----

    @Test
    fun `the built-in prefix for sites is geosite`() {
        RoutingEntries.builtInPrefix(BucketField.SITES) shouldBe "geosite:"
    }

    @Test
    fun `the built-in prefix for ips is geoip`() {
        RoutingEntries.builtInPrefix(BucketField.IPS) shouldBe "geoip:"
    }

    @Test
    fun `the built-in geo file name for sites is geosite dat`() {
        RoutingEntries.builtInGeoFileName(BucketField.SITES) shouldBe "geosite.dat"
    }

    @Test
    fun `the built-in geo file name for ips is geoip dat`() {
        RoutingEntries.builtInGeoFileName(BucketField.IPS) shouldBe "geoip.dat"
    }
}
