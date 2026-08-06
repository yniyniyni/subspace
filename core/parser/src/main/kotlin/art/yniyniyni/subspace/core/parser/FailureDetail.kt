// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

/**
 * What was wrong with an entry, as data rather than prose.
 *
 * Parser diagnostics are surfaced to users and may reach logs. The detail is
 * therefore built only from bounded enum values and numeric measurements; it
 * has no channel for input, credentials, addresses, or arbitrary prose.
 */
public sealed interface FailureDetail {
    /** Nothing to add beyond the failure reason. */
    public data object None : FailureDetail

    /** A field has the wrong fixed size. */
    public data class Length(
        val field: DetailField,
        val expected: Int,
        val actual: Int,
    ) : FailureDetail

    /** A numeric field is outside its permitted range. */
    public data class Range(
        val field: DetailField,
        val min: Int,
        val max: Int,
        val actual: Int,
    ) : FailureDetail

    /** A required field is absent or blank. */
    public data class Missing(
        val field: DetailField,
    ) : FailureDetail

    /** A field contains a supported-shape value this parser does not support. */
    public data class Unsupported(
        val field: DetailField,
    ) : FailureDetail

    /** A field or container is structurally malformed. */
    public data class Malformed(
        val field: DetailField,
    ) : FailureDetail
}

/**
 * Which bounded part of an entry a [FailureDetail] refers to.
 */
public enum class DetailField {
    Scheme,
    Uri,
    Address,
    Port,
    Uuid,
    Password,
    Method,
    PublicKey,
    ShortId,
    Fingerprint,
    Network,
    Security,
    Base64Body,
    JsonBody,
    YamlBody,
    Credential,
    AlterId,
}
