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

        val response = JSONObject(LibXray.invoke(request.toString()))
        if (!response.optBoolean("success", false)) {
            throw XrayException("libXray $method failed: ${response.optString("error")}")
        }
        return response.optJSONObject("data")
    }
}
