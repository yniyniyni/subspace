// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The two libXray calls a proxy-head measurement needs.
 *
 * An interface so [ProxyHeadProbe]'s orchestration — port, config, temp file,
 * cleanup — is testable on the JVM. `LibXray` is native; nothing that touches it
 * runs in a unit test.
 */
public interface XrayPingApi {
    /** §10.6: a free loopback port, per measurement. Never a literal. */
    public suspend fun allocatePort(): Int

    /**
     * Measures one server by starting a throwaway core and timing an HTTP HEAD
     * through it.
     *
     * @param timeoutSeconds libXray takes **seconds** here while returning
     *   **milliseconds** — see `docs/agent/research/2026-08-10-libxray-ping-semantics.md`
     *   §6. Passing millis here is a mistake no compiler will catch.
     * @return the measured delay in milliseconds.
     * @throws XrayException on any failure. libXray puts a sentinel delay
     *   (`10000`/`11000`) in the failure envelope's `data`; [LibXrayInvoke]
     *   discards `data` on failure, so that value is deliberately unreachable
     *   from here and cannot be mistaken for a measurement (§10.1).
     */
    public suspend fun ping(
        configPath: String,
        timeoutSeconds: Int,
        url: String,
        proxy: String,
    ): Int
}

/**
 * The production [XrayPingApi].
 *
 * Separate from [XrayController], which is single-use per connection: a
 * measurement is not a session, and hanging this off a controller instance would
 * imply a lifecycle it does not have.
 *
 * `ping` starts an independent instance through Go's `StartXray`, which never
 * touches the `coreServer` singleton that `runXray`/`stopXray`/`getXrayState`
 * guard — so a measurement does not disturb a running tunnel, and
 * [XrayController.isRunning] cannot see one in progress. **Read from upstream
 * source, not observed on hardware**; the milestone's device checklist is what
 * settles it.
 */
public class LibXrayPingApi(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : XrayPingApi {
    override suspend fun allocatePort(): Int =
        withContext(io) {
            val data = LibXrayInvoke.call("getFreePorts", JSONObject().put("count", 1))
            val ports = data?.optJSONArray("ports")
            if (ports == null || ports.length() == 0) {
                throw XrayException("libXray returned no free port")
            }
            ports.getInt(0)
        }

    override suspend fun ping(
        configPath: String,
        timeoutSeconds: Int,
        url: String,
        proxy: String,
    ): Int =
        withContext(io) {
            val payload =
                JSONObject()
                    .put("configPath", configPath)
                    .put("timeout", timeoutSeconds)
                    .put("url", url)
                    .put("proxy", proxy)
            val data = LibXrayInvoke.call("ping", payload)
            data?.optInt("delay") ?: throw XrayException("libXray returned no delay")
        }
}
