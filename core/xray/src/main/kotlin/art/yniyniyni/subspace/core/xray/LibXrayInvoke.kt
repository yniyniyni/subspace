// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import libXray.LibXray
import org.json.JSONObject

/**
 * Thrown when libXray rejects a request.
 *
 * The message can quote the config back, so callers must redact before it
 * reaches a log or the UI (§5.6). `ConnectionState.failure()` does this.
 */
public class XrayException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The libXray call envelope.
 *
 * v26.7.11 exposes exactly one entry point — `LibXray.invoke(String): String` —
 * taking `{"apiVersion":1,"method":"…","payload":{…}}` and returning
 * `{"success":bool,"data":…,"error":"…"}`.
 *
 * **It never throws.** A failure arrives inside the envelope, so an unchecked
 * `success` field is a silently ignored error — the precise shape §10.4 calls the
 * worst state this app can reach. Centralising the check here means no call site
 * can forget it.
 *
 * Verbatim API: `docs/agent/research/libxray-api.md`
 */
internal object LibXrayInvoke {
    private const val API_VERSION = 1

    /** @return the `data` object, or null for methods that return no data. */
    fun call(
        method: String,
        payload: JSONObject? = null,
    ): JSONObject? {
        val request =
            JSONObject()
                .put("apiVersion", API_VERSION)
                .put("method", method)
        if (payload != null) {
            request.put("payload", payload)
        }

        return parse(method, LibXray.invoke(request.toString()))
    }

    /**
     * Splits the envelope. Separate from [call] only because `LibXray.invoke` is
     * native and cannot run in a JVM test, while this — the part with a rule
     * worth pinning — can.
     *
     * **The `data` object is dropped whenever `success` is false, and that is
     * load-bearing rather than incidental.** `ping` is the method that makes it
     * matter: libXray answers a failed measurement with `success:false` *and* a
     * populated `{"delay":10000}` or `{"delay":11000}` — its `PingDelayError` and
     * `PingDelayTimeout` sentinels (`invoke.go`, `nodep/measure.go`). Returning
     * `data` here on a failure would hand a caller a number that looks measured
     * and is not, which is §10.1's failure mode arriving through an upstream API.
     * `LibXrayInvokeTest` pins this; do not "improve" it into surfacing `data` on
     * failure.
     */
    fun parse(
        method: String,
        response: String,
    ): JSONObject? {
        val envelope = JSONObject(response)
        if (!envelope.optBoolean("success", false)) {
            throw XrayException("libXray $method failed: ${envelope.optString("error")}")
        }
        return envelope.optJSONObject("data")
    }
}
