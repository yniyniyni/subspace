// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.parser.DetailField
import art.yniyniyni.subspace.core.parser.FailureDetail

/**
 * Resource-backed presentation of a parser diagnostic.
 *
 * Only bounded resource ids and numeric measurements cross this boundary.
 * Localized prose is resolved by Compose, never carried by `:core:parser`.
 */
internal sealed interface FailureDetailDisplay {
    val fieldRes: Int

    data class Field(
        val messageRes: Int,
        override val fieldRes: Int,
    ) : FailureDetailDisplay

    data class Length(
        override val fieldRes: Int,
        val expected: Int,
        val actual: Int,
    ) : FailureDetailDisplay

    data class Range(
        override val fieldRes: Int,
        val min: Int,
        val max: Int,
        val actual: Int,
    ) : FailureDetailDisplay
}

internal fun FailureDetail.toDisplay(): FailureDetailDisplay? =
    when (this) {
        FailureDetail.None -> null
        is FailureDetail.Length ->
            FailureDetailDisplay.Length(
                fieldRes = field.labelRes(),
                expected = expected,
                actual = actual,
            )
        is FailureDetail.Range ->
            FailureDetailDisplay.Range(
                fieldRes = field.labelRes(),
                min = min,
                max = max,
                actual = actual,
            )
        is FailureDetail.Missing ->
            FailureDetailDisplay.Field(
                messageRes = R.string.error_detail_missing,
                fieldRes = field.labelRes(),
            )
        is FailureDetail.Unsupported ->
            FailureDetailDisplay.Field(
                messageRes = R.string.error_detail_unsupported,
                fieldRes = field.labelRes(),
            )
        is FailureDetail.Malformed ->
            FailureDetailDisplay.Field(
                messageRes = R.string.error_detail_malformed,
                fieldRes = field.labelRes(),
            )
    }

@Suppress("CyclomaticComplexMethod")
internal fun DetailField.labelRes(): Int =
    when (this) {
        DetailField.Scheme -> R.string.error_detail_field_scheme
        DetailField.Uri -> R.string.error_detail_field_uri
        DetailField.Address -> R.string.error_detail_field_address
        DetailField.Port -> R.string.error_detail_field_port
        DetailField.Uuid -> R.string.error_detail_field_uuid
        DetailField.Password -> R.string.error_detail_field_password
        DetailField.Method -> R.string.error_detail_field_method
        DetailField.PublicKey -> R.string.error_detail_field_public_key
        DetailField.ShortId -> R.string.error_detail_field_short_id
        DetailField.Fingerprint -> R.string.error_detail_field_fingerprint
        DetailField.Network -> R.string.error_detail_field_network
        DetailField.Security -> R.string.error_detail_field_security
        DetailField.Base64Body -> R.string.error_detail_field_base64_body
        DetailField.JsonBody -> R.string.error_detail_field_json_body
        DetailField.YamlBody -> R.string.error_detail_field_yaml_body
        DetailField.Credential -> R.string.error_detail_field_credential
        DetailField.AlterId -> R.string.error_detail_field_alter_id
    }
