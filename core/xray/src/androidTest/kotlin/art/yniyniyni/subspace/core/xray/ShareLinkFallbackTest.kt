// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import androidx.test.ext.junit.runners.AndroidJUnit4
import art.yniyniyni.subspace.core.parser.SubscriptionParser
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented because it needs the libXray AAR. This is the only part of M2
 * that cannot run on the JVM, which is why it lives here and not in
 * `:core:parser` — §7's near-100% coverage target stays achievable because the
 * parser itself never depends on this.
 */
@RunWith(AndroidJUnit4::class)
class ShareLinkFallbackTest {
    @Test
    fun leavesACleanOutcomeUntouchedAndMakesNoCalls() {
        val text = "vless://70cc48c5-b2f4-4a1e-9f3d-0123456789ab@host.example:443"
        val outcome = SubscriptionParser.parse(text)
        outcome.failures.size shouldBe 0

        val after = ShareLinkFallback.retry(text, outcome)
        after.profiles.size shouldBe outcome.profiles.size
        after.failures.size shouldBe 0
    }

    @Test
    fun doesNotThrowWhenTheCoreRejectsALine() {
        val text = "wireguard://nonsense@host.example:443"
        val outcome = SubscriptionParser.parse(text)
        outcome.failures.size shouldBe 1

        // The core will reject this too. The contract is that we degrade to
        // the original failure, never throw and never lose the outcome.
        val after = ShareLinkFallback.retry(text, outcome)
        (after.profiles.size + after.failures.size) shouldBe 1
    }
}
