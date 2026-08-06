// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test

class FailureDetailTest {
    @Test
    fun `length detail carries the field and both counts`() {
        val detail = FailureDetail.Length(DetailField.PublicKey, expected = 43, actual = 12)

        detail.field shouldBe DetailField.PublicKey
        detail.expected shouldBe 43
        detail.actual shouldBe 12
    }

    @Test
    fun `a failure exposes its detail without a free-text field`() {
        val failure =
            parseFailure(
                index = 7,
                reason = ParseFailureReason.InvalidRealityKey,
                detail = FailureDetail.Length(DetailField.PublicKey, expected = 43, actual = 12),
            )

        failure.index shouldBe 7
        failure.reason shouldBe ParseFailureReason.InvalidRealityKey
        failure.detail shouldBe FailureDetail.Length(DetailField.PublicKey, expected = 43, actual = 12)
    }

    @Test
    fun `a detail with nothing to add is None, not an empty string`() {
        val failure =
            parseFailure(
                index = 0,
                reason = ParseFailureReason.EmptyInput,
                detail = FailureDetail.None,
            )

        failure.detail shouldBe FailureDetail.None
    }
}
