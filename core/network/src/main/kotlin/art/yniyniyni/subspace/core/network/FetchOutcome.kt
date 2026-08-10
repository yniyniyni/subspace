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
    /**
     * `x-hwid-not-supported` — the subscription needs a device ID.
     *
     * Classified on the header alone, **never** on the status: Remnawave answers a device-limit
     * refusal with an ordinary 200 and an empty body (§A.4.1). Requiring a 404 here, as this
     * comment once described, made the outcome unreachable in production and reported a
     * device-limited fetch as an empty server list.
     */
    HwidRequired,

    /** A 404 with no HWID marker — a wrong URL. A different problem with a different fix. */
    NotFound,

    /**
     * `x-hwid-max-devices-reached` — at the device cap.
     *
     * Not `x-hwid-limit`, despite the name. That one is a fixed v2rayTUN compatibility marker the
     * panel sets whenever HWID enforcement is engaged, including on successful responses; reading
     * it as "limit reached" turned every fetch from such a panel into a spurious failure (§A.4.1).
     */
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

    /**
     * @property detail the *cause* behind [reason], when one is known — the throwable's simple
     *   class name (`SSLHandshakeException`, `ConnectException`, ...), never its message.
     *
     *   M4's device run is the argument for this field existing. One host alternated between
     *   [FetchFailure.TlsFailure] and [FetchFailure.Unreachable] minutes apart and recovered on
     *   its own; answering "is this a reset in the path, an expired certificate, or a protocol
     *   mismatch?" took four probes from outside the app, because every one of those collapses
     *   to the same taxonomy member and the exception was discarded at the catch. The category
     *   is what the *user* is told (§7 is deliberately closed); this is what a log or a bug
     *   report needs.
     *
     *   The message is excluded on purpose, not overlooked: TLS and DNS exception messages
     *   routinely embed the hostname (`Hostname x.example not verified`, `Unable to resolve
     *   host "x.example"`), and a subscription URL's host is a secret under §5.6. A JDK/OkHttp
     *   class name is shape, not content — the same line [FetchOutcome.Success.toString] draws
     *   between header keys and header values.
     */
    public data class Failed(
        val reason: FetchFailure,
        val detail: String? = null,
    ) : FetchOutcome
}
