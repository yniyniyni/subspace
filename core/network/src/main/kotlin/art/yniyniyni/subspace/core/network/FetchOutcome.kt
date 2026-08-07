// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network

/**
 * Why a fetch did not produce a body.
 *
 * A closed vocabulary with **no payload**, deliberately (§5.6): the M2 residuals
 * record argues at length that free-text-plus-regex-redaction eventually loses,
 * and this is a new surface, so it starts closed rather than being retrofitted.
 * No member can carry a URL, a body, or a server address.
 *
 * §10.4: the user needs a diagnosis, not a generic failure. The first three
 * members are the entire reason this type is not a boolean.
 */
public enum class FetchFailure {
    /** 404 with `x-hwid-not-supported` — the subscription needs a device ID. */
    HwidRequired,

    /** 404 without it — a wrong URL. A different problem with a different fix. */
    NotFound,

    /** `x-hwid-max-devices-reached` or `x-hwid-limit` — at the device cap. */
    DeviceLimitReached,

    Unreachable,
    TimedOut,
    TlsFailure,
    ClientError,
    ServerError,
}

/** The result of one [SubscriptionFetcher.fetch]. */
public sealed interface FetchOutcome {
    /** @property headers every response header, lower-cased. The allow-list is the registry's job. */
    public data class Success(val body: String, val headers: Map<String, String>) : FetchOutcome {
        // §5.6: body is the raw subscription content — server addresses,
        // credentials, everything. The generated data-class toString() would
        // print it verbatim; this is a structural guard, not a fix for an
        // active leak (see SubscriptionRequest's identical override).
        //
        // headers gets the same treatment for its *values*, not its keys:
        // the panel can echo HWID/device-limit state and provider-specific
        // headers back (§A.4.1), and a header value is exactly as much a
        // secret as the body it describes. Key names alone (`profile-title`,
        // `x-hwid-active`, ...) are shape, not content, and keeping them
        // visible is what makes a redacted instance still useful for
        // debugging which headers came back.
        override fun toString(): String =
            "Success(body=<redacted, ${body.length} chars>, headers=${headers.keys})"
    }

    public data class Failed(val reason: FetchFailure) : FetchOutcome
}
