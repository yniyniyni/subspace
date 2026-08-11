// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.model.LatencyOptions
import art.yniyniyni.subspace.core.model.LatencyOutcome
import art.yniyniyni.subspace.core.model.LatencyResult
import art.yniyniyni.subspace.core.model.Profile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The ping config needs a `dns` block because [XrayConfigGenerator] always
 * writes one. It resolves the server's own hostname inside the throwaway
 * instance and never governs the user's traffic — that is §5.2's concern and
 * belongs to the tunnel's config, not to a measurement.
 */
private const val PING_DNS = "1.1.1.1"

/**
 * Measures a server by starting a throwaway Xray instance and timing an HTTP
 * HEAD through it.
 *
 * Unlike [TcpProbe] this proves the proxy actually carries traffic, at the cost
 * of a full core start per measurement — which is why callers bound how many run
 * at once.
 *
 * Every failure is [LatencyOutcome.UNREACHABLE]. The timeout/unreachable split
 * exists only in [TcpProbe]: libXray encodes a failed ping as `success:false`
 * with a sentinel delay in `data`, and [LibXrayInvoke] discards `data` on
 * failure precisely so `10000` can never be rendered as a measurement (§10.1).
 * Recovering the distinction would mean parsing an error string that quotes the
 * config (§5.6), which is a worse trade than losing it.
 */
public class ProxyHeadProbe(
    private val api: XrayPingApi,
    private val cacheDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    // The catches below are deliberate swallows, for §5.6: an XrayException's
    // message quotes the config that produced it, which carries the address,
    // UUID and REALITY key. The failure becomes a typed outcome instead.
    @Suppress("SwallowedException")
    public suspend fun measure(
        profile: Profile,
        options: LatencyOptions,
    ): LatencyResult =
        withContext(io) {
            val port =
                allocatePortWithOneRetry()
                    ?: return@withContext LatencyResult.failed(LatencyOutcome.UNREACHABLE)

            val settings = TunnelSettings(socksPort = port, dnsServer = PING_DNS, enableSniffing = false)
            // Resolved before any file is written: an unsupported protocol must
            // cost nothing, not a config write we immediately delete.
            val json =
                when (val config = XrayConfigGenerator.generate(profile, settings)) {
                    is ConfigResult.Unsupported -> return@withContext LatencyResult.failed(LatencyOutcome.UNSUPPORTED)
                    is ConfigResult.Ok -> config.json
                }

            var file: File? = null
            try {
                // A unique name, never the tunnel's `xray-config.json`: this can
                // run while a session is live, and overwriting that file would
                // hand the next validate/start someone else's config.
                val target = File(cacheDir, "ping-${UUID.randomUUID()}.json")
                // Recorded *before* the write, not after: a partial write (disk
                // full, quota, I/O error) throws with the file already created, and
                // assigning afterwards left `file` null so the `finally` deleted
                // nothing — stranding a partially written config carrying the
                // address, UUID and REALITY key (§5.6).
                file = target
                target.writeText(json)
                LatencyResult.ok(
                    api.ping(
                        configPath = target.absolutePath,
                        timeoutSeconds = options.timeoutSeconds,
                        url = options.checkUrl,
                        proxy = "socks5://127.0.0.1:$port",
                    ),
                )
            } catch (e: XrayException) {
                LatencyResult.failed(LatencyOutcome.UNREACHABLE)
            } catch (e: IOException) {
                LatencyResult.failed(LatencyOutcome.UNREACHABLE)
            } finally {
                // §5.6: this file holds the address, UUID and REALITY key. It goes
                // away on every path, including cancellation unwinding through here.
                file?.delete()
            }
        }

    /**
     * `getFreePorts` binds `localhost:0` and closes the listener, so the port can
     * be taken between allocation and use (`libxray-api.md` §5). One retry, then
     * give up — a loop here would spin on a genuinely exhausted port range.
     */
    @Suppress("SwallowedException")
    private suspend fun allocatePortWithOneRetry(): Int? =
        try {
            api.allocatePort()
        } catch (first: XrayException) {
            try {
                api.allocatePort()
            } catch (retry: XrayException) {
                null
            }
        }
}
