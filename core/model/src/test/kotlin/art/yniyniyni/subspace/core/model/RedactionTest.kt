// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/**
 * ARCHITECTURE.md §5.6: server addresses, UUIDs, REALITY keys, and subscription
 * URLs are secrets, and must be redacted in every log path including crash output.
 *
 * These tests come first because redaction that depends on remembering to call a
 * helper is redaction that eventually fails. The failure is also silent — a leaked
 * key looks exactly like a working app.
 */
class RedactionTest {
    @Test
    fun `strips uuids`() {
        val input = "user 70cc48c5-b2f4-4a1e-9f3d-0123456789ab failed to authenticate"
        redact(input) shouldNotContain "70cc48c5"
    }

    @Test
    fun `strips ipv4 addresses`() {
        redact("dial tcp 203.0.113.44:443 refused") shouldNotContain "203.0.113.44"
    }

    @Test
    fun `strips hostnames`() {
        redact("tls handshake to secret.example.com failed") shouldNotContain "secret.example.com"
    }

    @Test
    fun `strips base64 blobs of reality key length`() {
        val key = "SGVsbG8gdGhpcyBpcyBhIGZha2UgcmVhbGl0eSBrZXk"
        redact("publicKey=$key") shouldNotContain key
    }

    @Test
    fun `strips urls`() {
        redact("fetching https://sub.example.com/abc123") shouldNotContain "sub.example.com"
    }

    @Test
    fun `keeps the surrounding message intact`() {
        // A redacted diagnostic is only useful if you can still tell what happened.
        redact("dial tcp 203.0.113.44:443 refused") shouldBe "dial tcp <redacted>:443 refused"
    }

    @Test
    fun `is idempotent`() {
        // ConnectionStateParcel redacts on both sides of the IPC boundary, so a
        // double pass must not mangle an already-redacted string.
        val once = redact("user 70cc48c5-b2f4-4a1e-9f3d-0123456789ab")
        redact(once) shouldBe once
    }

    @Test
    fun `strips a whole vless share link`() {
        val link = "vless://70cc48c5-b2f4-4a1e-9f3d-0123456789ab@example.com:443?security=reality"
        val out = redact("failed to parse $link")
        out shouldNotContain "70cc48c5"
        out shouldNotContain "example.com"
    }

    /**
     * M2 made IPv6 a first-class parse target, so an IPv6 server address is now
     * something that genuinely reaches a diagnostic — `TunnelService`'s failure
     * path passes `XrayException.message`, whose own KDoc warns the core quotes
     * the config back.
     */
    @Test
    fun `strips ipv6 literals`() {
        redact("failed to dial 2001:db8::1 port 443") shouldNotContain "2001:db8::1"
        redact("dial tcp [fd00:1:2:3::1]:443 refused") shouldNotContain "fd00"
        redact("listener on ::1 closed") shouldNotContain "::1"
        redact("peer 2001:0db8:85a3:0000:0000:8a2e:0370:7334 gone") shouldNotContain "8a2e"
    }

    /**
     * A single-label host matches no shape-based pattern — there is nothing
     * about `vpnserver` that distinguishes it from an ordinary word — so
     * position is the only signal left.
     */
    @Test
    fun `strips single-label hosts named by a destination word`() {
        redact("host vpnserver unreachable") shouldNotContain "vpnserver"
        redact("dial tcp vpnserver:443 refused") shouldNotContain "vpnserver"
        redact("server intranet refused the connection") shouldNotContain "intranet"
    }

    /**
     * The other half of the contract, and the reason the label rule runs last
     * and consults a stop-word set: §10.4 makes this redacted string the only
     * diagnostic a user can hand back, so redaction that eats the message is
     * not a safer trade — it is a different failure.
     */
    @Test
    fun `leaves diagnostics that carry no secret intact`() {
        val harmless =
            listOf(
                "link is not a usable URI",
                "port must be 1..65535, got 70000",
                "publicKey must be 43 characters, got 12",
                "vless address is missing",
                "vmess address is missing",
                "proxy has no server",
                "input contains no server entries",
                "entry is not a share link",
                "proxy type is not supported",
                "vless outbound has no users",
            )
        harmless.forEach { message ->
            withClue(message) { redact(message) shouldBe message }
        }
    }

    @Test
    fun `keeps the port when the address beside it is redacted`() {
        // The label rule must not swallow "dial tcp <address>:443" whole — the
        // port is the diagnostic. It runs after the shape-based patterns for
        // exactly this reason.
        redact("failed to dial 2001:db8::1 port 443") shouldContain "443"
        redact("dial tcp 203.0.113.44:443 refused") shouldBe "dial tcp <redacted>:443 refused"
    }

    @Test
    fun `is idempotent for the ipv6 and labelled-host rules too`() {
        listOf(
            "failed to dial 2001:db8::1 port 443",
            "host vpnserver unreachable",
            "dial tcp 203.0.113.44:443 refused",
        ).forEach { message ->
            val once = redact(message)
            withClue(message) { redact(once) shouldBe once }
        }
    }

    /**
     * The four shapes `docs/agent/research/2026-07-27-m2-residuals-for-m3.md` §2
     * demonstrated by execution, not by reading. All four come out of Go, and
     * `TunnelService` passes `XrayException.message` straight into `failure()`.
     *
     * None of them are reachable from `:core:parser` any more — its diagnostics
     * are a closed vocabulary as of Task 2 — which is precisely why they need
     * pinning here: nothing else in the tree would notice their return.
     */
    @Test
    fun `Go resolver errors do not leak the host`() {
        redact("dial tcp: lookup vpnserver: no such host") shouldNotContain "vpnserver"
    }

    @Test
    fun `Go JSON echoes do not leak the address`() {
        redact("""{"address":"vpnserver","port":443}""") shouldNotContain "vpnserver"
    }

    @Test
    fun `key=value diagnostics do not leak the address`() {
        redact("address=vpnserver port=443") shouldNotContain "vpnserver"
    }

    @Test
    fun `bare host prefixes do not leak the host`() {
        redact("vpnserver: connection refused") shouldNotContain "vpnserver"
    }

    /**
     * Residual §2 also recorded the opposite failure: `IPV6_PATTERN` matches any
     * colon run, so a clock time was being destroyed to protect nothing.
     */
    @Test
    fun `a timestamp is not mistaken for an IPv6 address`() {
        redact("started at 12:34:56") shouldContain "12:34:56"
    }

    /**
     * The other half of the four above. §10.4 makes this string the only
     * diagnostic a user can hand back, so "the host is gone" is half a test —
     * these pin what is *left*, which is what makes the failure debuggable.
     *
     * The `lookup` case's expectation changed when `BARE_HOST_PREFIX_PATTERN`
     * moved to a token-boundary anchor (see that pattern's KDoc in
     * `Redaction.kt`): the rule now claims `vpnserver:` directly, ahead of
     * `LABELLED_HOST_PATTERN`, and because it preserves what follows the
     * matched token, the colon that was genuinely part of the Go error chain
     * — `lookup <host>:` — now survives instead of being swallowed with the
     * host. "dial tcp: lookup <redacted>:
     * no such host" is at least as readable as the old "dial tcp: lookup
     * <redacted> no such host": it keeps the shape of Go's own error chaining
     * intact, which is the whole point of pinning these strings rather than
     * just asserting the host is gone.
     */
    @Test
    fun `keeps the Go diagnostic readable around the redacted host`() {
        val cases =
            mapOf(
                "dial tcp: lookup vpnserver: no such host" to "dial tcp: lookup <redacted>: no such host",
                """{"address":"vpnserver","port":443}""" to """{"address":"<redacted>","port":443}""",
                "address=vpnserver port=443" to "address=<redacted> port=443",
                "vpnserver: connection refused" to "<redacted>: connection refused",
            )
        cases.forEach { (input, expected) ->
            withClue(input) { redact(input) shouldBe expected }
        }
    }

    @Test
    fun `is idempotent for the Go error shapes too`() {
        listOf(
            "dial tcp: lookup vpnserver: no such host",
            """{"address":"vpnserver","port":443}""",
            "address=vpnserver port=443",
            "vpnserver: connection refused",
            "started at 12:34:56",
        ).forEach { message ->
            val once = redact(message)
            withClue(message) { redact(once) shouldBe once }
        }
    }

    /**
     * The four shapes above are what the Go core returns *by itself*. In
     * production none of them reach `redact()` unwrapped:
     * `LibXrayInvoke.call` throws `XrayException("libXray $method failed:
     * ${response.optString("error")}")`, and `TunnelService.failStart` passes
     * `cause.message` straight into `ConnectionState.failure()`. So the real
     * call path always hands `redact()` a string that already carries the
     * `"libXray <method> failed: "` prefix.
     *
     * A code review proved by execution that a `^`-anchored bare-host-prefix
     * pattern only ever looks at literal index 0 of the string, so this
     * prefix defeats it — the one shape of the four that relies on being
     * first is exactly the one shape production never hands it first.
     */
    @Test
    fun `Go error shapes do not leak the host once wrapped by libXray's own prefix`() {
        val prefix = "libXray InvokeTun failed: "
        redact(prefix + "dial tcp: lookup vpnserver: no such host") shouldNotContain "vpnserver"
        redact(prefix + """{"address":"vpnserver","port":443}""") shouldNotContain "vpnserver"
        redact(prefix + "address=vpnserver port=443") shouldNotContain "vpnserver"
        redact(prefix + "vpnserver: connection refused") shouldNotContain "vpnserver"
    }

    /**
     * §10.4: `FailureReason.GeoDataMissing`'s entire purpose is naming exactly
     * which `.dat` file is missing so the user re-downloads it instead of going
     * looking for a broken server. A filename is shape, not content (§5.6) — it
     * is not the "server address" this function exists to protect, and if this
     * regresses the failure reads "Geo data missing — `<redacted>, <redacted>`",
     * which tells the user nothing.
     */
    @Test
    fun `keeps geo database filenames intact`() {
        redact("geoip.dat, geosite.dat") shouldBe "geoip.dat, geosite.dat"
        redact("geoip.dat") shouldBe "geoip.dat"
        redact("geoip.dat, geosite.dat, mynets.dat") shouldBe "geoip.dat, geosite.dat, mynets.dat"
    }

    /**
     * Task-12 review, Finding 5: [GEO_FILE_LIST_MESSAGE]'s separator (`", "`) is
     * coupled to `TunnelService.resolveRouting`'s
     * `resolution.missing.sorted().joinToString(", ")` by a comment only — there
     * is nothing that breaks if one changes and not the other, and the failure
     * mode is silent: the exemption stops matching, filenames get redacted away,
     * and the diagnostic §10.4 exists for degrades to unreadable with no test
     * failure anywhere pointing at why. `:core:model` cannot depend on `:service`
     * to call the real joiner, so this reproduces its exact call shape instead —
     * `sorted()` then `joinToString(", ")` over real, [isGeoFileName]-shaped
     * names — and asserts the result survives [redact] byte for byte.
     */
    @Test
    fun `survives the exact joined form TunnelService resolveRouting produces`() {
        val missing = setOf("mynets.dat", "geoip.dat", "geosite.dat")
        val message = missing.sorted().joinToString(", ")

        redact(message) shouldBe message
    }

    /**
     * The exemption is anchored to the **whole** message, and this is the test
     * that says so.
     *
     * Exempting any *token* shaped like `<name>.dat` would exempt it in every
     * string this function ever sees, including the config libXray quotes back
     * when `testXray` rejects one. `RoutingEntries` accepts any domain body
     * without whitespace, `/` or `:`, so `corp.internal.dat` is a legal routing
     * rule — and a routing rule is browsing data under §5.6. If either assertion
     * here starts passing a hostname through, the exemption has been widened
     * from "this message is a filename list" to "this token looks like a
     * filename", and that is a leak.
     *
     * ## The residual, stated plainly
     *
     * A message that is *nothing but* a `.dat`-suffixed hostname — `redact(
     * "corp.internal.dat")` on its own — is passed through, because a custom geo
     * source may legitimately be called `corp.internal.dat` and the grammar
     * cannot tell the two apart. That is not closable here; what bounds it is
     * that no real caller produces such a message. Every diagnostic that reaches
     * this function carries prose, punctuation or JSON around the token, and all
     * of those fail the whole-message match — which is exactly what the cases
     * below cover.
     */
    @Test
    fun `a dat-suffixed hostname is redacted wherever a real message would put it`() {
        redact("dial tcp corp.internal.dat:443: refused") shouldNotContain "corp.internal.dat"
        redact("""{"domain":["corp.internal.dat"]}""") shouldNotContain "corp.internal.dat"
        redact("proxying to secret.example.dat") shouldNotContain "secret.example.dat"
        redact("geoip.dat secret.example.dat") shouldNotContain "secret.example.dat"
    }

    /**
     * The deliberate trade: outside a pure filename list, a `.dat` name is
     * redacted like any other host-shaped token. Losing the filename from free
     * text is the price of the anchoring above, and it costs nothing in practice
     * — `GeoDataMissing`'s detail is only ever the bare list, never prose.
     */
    @Test
    fun `a geo filename inside a larger message is redacted like any other token`() {
        redact("failed to parse geoip.dat") shouldBe "failed to parse <redacted>"
    }

    @Test
    fun `a quoted config leaks neither a dat-suffixed rule nor a real server`() {
        val key = "SGVsbG8gdGhpcyBpcyBhIGZha2UgcmVhbGl0eSBrZXk"
        val message =
            """{"domain":["corp.internal.dat"],"address":"secret.example.com",""" +
                """"id":"70cc48c5-b2f4-4a1e-9f3d-0123456789ab","publicKey":"$key"}"""

        val out = redact(message)

        out shouldNotContain "corp.internal.dat"
        out shouldNotContain "secret.example.com"
        out shouldNotContain "70cc48c5"
        out shouldNotContain key
    }

    /** `ConnectionStateParcel` redacts on both sides of the binder, so the exemption must survive twice. */
    @Test
    fun `the filename list is idempotent under a second pass`() {
        val once = redact("geoip.dat, geosite.dat")

        redact(once) shouldBe once
    }
}
