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
