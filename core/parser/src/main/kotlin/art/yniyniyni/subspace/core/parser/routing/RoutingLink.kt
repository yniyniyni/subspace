// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser.routing

import art.yniyniyni.subspace.core.model.RoutingProfile

/** The three routing deeplink verbs documented by Happ (research §2). */
public enum class RoutingVerb {
    /** Adds a profile without overriding a currently active one. */
    Add,

    /** Adds a profile and requests its activation. */
    OnAdd,

    /** Disables routing and carries no profile payload. */
    Off,
}

/** A closed, value-free vocabulary describing why a routing link was rejected. */
public enum class ImportProblem {
    /** The text has neither a supported scheme nor a `routing/` path. */
    NotARoutingLink,

    /** The link names a routing verb this parser does not support. */
    UnknownVerb,

    /** The encoded profile payload cannot be decoded as base64. */
    MalformedBase64,

    /** The decoded payload is not a JSON object. */
    MalformedJson,

    /** The JSON object has no usable profile name. */
    MissingName,

    /** The profile name exceeds the import limit. */
    NameTooLong,

    /** A present `RouteOrder` is not a permutation of every route outcome. */
    InvalidRouteOrder,

    /** An entry bucket has an invalid shape or an entry Xray would reject. */
    InvalidEntry,

    /** A geo URL is not an absolute HTTPS URL without credentials. */
    MalformedGeoUrl,

    /** A geo URL uses plaintext HTTP. */
    InsecureGeoUrl,

    /** The decoded payload is larger than [MAX_PROFILE_BYTES]. */
    TooLarge,
}

/** The result of parsing one routing deeplink. */
public sealed interface ImportResult {
    /** A successfully decoded profile together with its requested action. */
    public data class Imported(
        val verb: RoutingVerb,
        val profile: RoutingProfile,
    ) : ImportResult

    /** The `happ://routing/off` action. It does not delete stored profiles. */
    public data object DisableRouting : ImportResult

    /** A rejected link represented without echoing an untrusted value. */
    public data class Invalid(
        val problem: ImportProblem,
    ) : ImportResult
}

/** Maximum decoded routing-profile size accepted from an untrusted link. */
public const val MAX_PROFILE_BYTES: Int = 512 * 1024
