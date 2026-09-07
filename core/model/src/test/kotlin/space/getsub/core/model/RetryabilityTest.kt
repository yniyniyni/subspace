// SPDX-License-Identifier: AGPL-3.0-or-later
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Spec §2.2. These are invariants, not samples: each grouping is a decision
 * about whether the app retries forever, and getting one wrong produces either
 * a spinner over a config the core has already refused, or a tunnel that gives
 * up on a transient failure it would have recovered from.
 */
class RetryabilityTest {
    @Test
    fun everyReasonIsClassified() {
        // No reason may be left out. If this fails after a new FailureReason is
        // added, classify it in Retryability.kt — do not widen the assertion.
        FailureReason.entries.size shouldBe 14
        FailureReason.entries.forEach { reason -> reason.retryability() }
    }

    @Test
    fun configAndConsentFailuresAreTerminal() {
        listOf(
            FailureReason.VpnPermissionMissing,
            FailureReason.ProfileDecodeFailed,
            FailureReason.ProtocolNotSupported,
            FailureReason.ConfigGenerationFailed,
            FailureReason.ConfigRejected,
            FailureReason.PassthroughRejectedAtConnect,
            FailureReason.PassthroughOverrideUnavailable,
            FailureReason.PerAppAllowListEmpty,
            FailureReason.Revoked,
        ).forEach { reason -> reason.retryability() shouldBe Retryability.Terminal }
    }

    /**
     * Looks transient and is not. Its own KDoc says it is reachable when a geo
     * file is deleted or storage cleared after activation, and that it resolves
     * when the user re-downloads — an action, not a wait. Retrying on a timer
     * would hide the one thing this reason exists to tell them.
     */
    @Test
    fun missingGeoDataIsTerminalBecauseOnlyTheUserCanFixIt() {
        FailureReason.GeoDataMissing.retryability() shouldBe Retryability.Terminal
    }

    @Test
    fun resourceFailuresAreRetryable() {
        listOf(
            FailureReason.CoreStartFailed,
            FailureReason.TunnelStartFailed,
            FailureReason.PortAllocationFailed,
        ).forEach { reason -> reason.retryability() shouldBe Retryability.Retryable }
    }

    /**
     * Spec §2.3: `establish()` returns the same null for resource pressure
     * (retryable) and for vanished VPN consent (terminal), and nothing at that
     * call site distinguishes them. Capped rather than unbounded so a revoked
     * consent cannot spin forever.
     */
    @Test
    fun tunEstablishFailedIsRetryableButCapped() {
        FailureReason.TunEstablishFailed.retryability() shouldBe Retryability.RetryableCapped
        TUN_ESTABLISH_ATTEMPT_CAP shouldBe 3
    }
}
