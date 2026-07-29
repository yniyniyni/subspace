// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.parser.DetailField
import art.yniyniyni.subspace.core.parser.FailureDetail
import io.kotest.matchers.shouldBe
import org.junit.Test

class FailureDetailDisplayTest {
    @Test
    fun `every detail variant maps to bounded display data`() {
        val expected =
            mapOf(
                FailureDetail.Length(DetailField.PublicKey, expected = 43, actual = 12) to
                    FailureDetailDisplay.Length(
                        fieldRes = R.string.error_detail_field_public_key,
                        expected = 43,
                        actual = 12,
                    ),
                FailureDetail.Range(DetailField.Port, min = 1, max = 65_535, actual = 70_000) to
                    FailureDetailDisplay.Range(
                        fieldRes = R.string.error_detail_field_port,
                        min = 1,
                        max = 65_535,
                        actual = 70_000,
                    ),
                FailureDetail.Missing(DetailField.Password) to
                    FailureDetailDisplay.Field(
                        messageRes = R.string.error_detail_missing,
                        fieldRes = R.string.error_detail_field_password,
                    ),
                FailureDetail.Unsupported(DetailField.Method) to
                    FailureDetailDisplay.Field(
                        messageRes = R.string.error_detail_unsupported,
                        fieldRes = R.string.error_detail_field_method,
                    ),
                FailureDetail.Malformed(DetailField.JsonBody) to
                    FailureDetailDisplay.Field(
                        messageRes = R.string.error_detail_malformed,
                        fieldRes = R.string.error_detail_field_json_body,
                    ),
            )

        expected.forEach { (detail, display) ->
            detail.toDisplay() shouldBe display
        }
        FailureDetail.None.toDisplay() shouldBe null
    }

    @Test
    fun `every detail field maps to a localized bounded label`() {
        DetailField.entries.map(DetailField::labelRes) shouldBe
            listOf(
                R.string.error_detail_field_scheme,
                R.string.error_detail_field_uri,
                R.string.error_detail_field_address,
                R.string.error_detail_field_port,
                R.string.error_detail_field_uuid,
                R.string.error_detail_field_password,
                R.string.error_detail_field_method,
                R.string.error_detail_field_public_key,
                R.string.error_detail_field_short_id,
                R.string.error_detail_field_fingerprint,
                R.string.error_detail_field_network,
                R.string.error_detail_field_security,
                R.string.error_detail_field_base64_body,
                R.string.error_detail_field_json_body,
                R.string.error_detail_field_yaml_body,
                R.string.error_detail_field_credential,
                R.string.error_detail_field_alter_id,
            )
    }
}
