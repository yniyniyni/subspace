// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

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

    /**
     * @param env The `env` object PR #133 restored on the invoke request —
     *   `third_party/libxray-patches/0001-restore-invoke-env.patch` — and the
     *   only route by which xray-core learns `XRAY_LOCATION_ASSET` on Android
     *   (research §2b). Included only when non-null: an unpatched libXray
     *   silently drops an unknown `env` key, so omitting it when there is
     *   nothing to say keeps the request byte-identical to before this existed.
     * @return the `data` object, or null for methods that return no data.
     */
    fun call(
        method: String,
        payload: JSONObject? = null,
        env: XrayEnv? = null,
    ): JSONObject? {
        val request =
            JSONObject()
                .put("apiVersion", API_VERSION)
                .put("method", method)
        if (env != null) {
            request.put("env", env.toJson())
        }
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

/**
 * The `env` object on a libXray invoke request.
 *
 * Field names are upstream's own — `LibXrayEnvJson` in
 * `third_party/libxray-patches/0001-restore-invoke-env.patch` (PR #133),
 * copied rather than renamed. Matching the wire shape exactly is what keeps
 * that patch reversible: when upstream ships `env` again, only the pin in
 * `scripts/fetch-native.sh` changes and nothing here does (see that patch
 * directory's README).
 *
 * Only [assetLocation] has a production caller today — `XrayController`
 * points it at [space.getsub.core.data.GeoAssetRepository]'s
 * install directory (research §2b). [certLocation] and [tunFd] exist because
 * they are part of upstream's shape, not because this project uses them.
 */
internal data class XrayEnv(
    val assetLocation: String? = null,
    val certLocation: String? = null,
    val tunFd: String? = null,
) {
    /** Omits absent fields rather than sending them empty — matches `omitempty` on the Go struct. */
    fun toJson(): JSONObject =
        JSONObject().apply {
            assetLocation?.let { put("xray.location.asset", it) }
            certLocation?.let { put("xray.location.cert", it) }
            tunFd?.let { put("xray.tun.fd", it) }
        }
}
