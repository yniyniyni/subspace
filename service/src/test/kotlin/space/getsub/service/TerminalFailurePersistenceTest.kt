// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.FailureReason

/**
 * [encodeFailureReason]/[decodeFailureReason]: the codec `TerminalStateMemory`'s
 * persistence seam uses to cross Room's plain-string rows (ARCHITECTURE.md §11 row 7,
 * Task 20).
 */
class TerminalFailurePersistenceTest {
    @Test
    fun `every FailureReason round-trips through encode and decode`() {
        for (reason in FailureReason.entries) {
            decodeFailureReason(encodeFailureReason(reason)) shouldBe reason
        }
    }

    @Test
    fun `a garbage name decodes to null`() {
        decodeFailureReason("NotARealFailureReason").shouldBeNull()
    }

    @Test
    fun `a blank name decodes to null`() {
        decodeFailureReason("").shouldBeNull()
    }

    @Test
    fun `a null name decodes to null`() {
        decodeFailureReason(null).shouldBeNull()
    }

    @Test
    fun `decoding is case-sensitive, so a near-miss is garbage rather than a silent match`() {
        decodeFailureReason(FailureReason.Revoked.name.lowercase()).shouldBeNull()
    }
}
