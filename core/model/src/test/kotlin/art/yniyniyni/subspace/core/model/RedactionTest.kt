// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

import io.kotest.matchers.shouldBe
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
}
