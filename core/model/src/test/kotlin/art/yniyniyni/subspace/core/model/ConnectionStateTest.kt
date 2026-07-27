// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class ConnectionStateTest {
    @Test
    fun `failure redacts the detail string`() {
        // libXray quotes the offending config back in its error text, so the
        // realistic failure detail carries both an address and a UUID (§5.6).
        val state =
            failure(
                FailureReason.CoreStartFailed,
                "dial tcp 203.0.113.44:443: user 70cc48c5-b2f4-4a1e-9f3d-0123456789ab rejected",
            )
        state.detail shouldNotContain "203.0.113.44"
        state.detail shouldNotContain "70cc48c5"
    }

    @Test
    fun `failure preserves the reason`() {
        failure(FailureReason.ConfigRejected, "whatever").reason shouldBe FailureReason.ConfigRejected
    }

    @Test
    fun `redacting an already redacted failure changes nothing`() {
        // ConnectionStateParcel redacts on both sides of the IPC boundary.
        val once = failure(FailureReason.CoreStartFailed, "dial tcp 203.0.113.44:443 refused")
        failure(once.reason, once.detail).detail shouldBe once.detail
    }

    /**
     * The redaction in [failure] is only worth anything if it cannot be walked
     * around. A public constructor or a public generated `copy()` would let
     * `ConnectionState.Failed(reason, raw)` or
     * `existing.copy(detail = raw)` produce an unredacted instance without ever
     * calling [failure] — and [ConnectionState.Failed.detail] comes from
     * `XrayException`, which quotes the config back (§5.6).
     *
     * Asserted by reflection rather than by a commented-out line, because the
     * thing being checked is precisely that the alternative does not compile,
     * and a test that does not compile is not a test.
     */
    @Test
    fun `Failed cannot be constructed or copied around redaction`() {
        val type = ConnectionState.Failed::class.java

        // Synthetic members are excluded because they are exactly what makes
        // this work: Kotlin emits a public ACC_SYNTHETIC constructor taking a
        // DefaultConstructorMarker as the bridge the companion uses to reach
        // the private one. It is unnameable from Kotlin source, so it closes
        // nothing and asserting on it would only assert the compiler's
        // internals.
        type.declaredConstructors
            .filterNot { it.isSynthetic }
            .none { java.lang.reflect.Modifier.isPublic(it.modifiers) } shouldBe true

        type.declaredMethods
            .filter { it.name.startsWith("copy") }
            .filterNot { it.isSynthetic }
            .none { java.lang.reflect.Modifier.isPublic(it.modifiers) } shouldBe true
    }

    @Test
    fun `every startup stage is distinct`() {
        // The stage is the only diagnostic available when §5.6 forbids logging
        // the config, so a duplicated ordinal would be a real loss.
        StartupStage.entries
            .map { it.ordinal }
            .toSet()
            .size shouldBe StartupStage.entries.size
    }
}
