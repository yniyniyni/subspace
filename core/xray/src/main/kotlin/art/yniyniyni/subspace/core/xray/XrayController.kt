// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject
import java.io.File

/**
 * Owns the libXray lifecycle for one tunnel session.
 *
 * Every call is slow and runs on IO (§5.3) — the connect button must stay
 * responsive through the whole start sequence.
 *
 * Instances are single-use: create one per connection, so a stale protector
 * reference cannot survive a service recreation (§5.1).
 */
public class XrayController(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * §10.6: never a hardcoded port. A fixed port collides with other proxy apps.
     *
     * libXray allocates by binding `localhost:0` and closing the listener, so
     * there is a small race between allocation and Xray binding it. Still far
     * better than a literal.
     */
    public suspend fun allocatePort(): Int =
        withContext(io) {
            val data = LibXrayInvoke.call("getFreePorts", JSONObject().put("count", 1))
            val ports = data?.optJSONArray("ports")
            if (ports == null || ports.length() == 0) {
                throw XrayException("libXray returned no free port")
            }
            ports.getInt(0)
        }

    /**
     * §6: validate before starting. A malformed config makes libXray fail in a way
     * that is hard to attribute; catching it here produces a real error instead.
     *
     * Takes a [File] because `testXray` accepts only a path — and validating the
     * same file [start] then runs means the bytes checked are the bytes used.
     */
    public suspend fun validate(configFile: File) {
        withContext(io) {
            LibXrayInvoke.call("testXray", JSONObject().put("configPath", configFile.absolutePath))
        }
    }

    /**
     * Starts the core.
     *
     * [protector] is wired here, on every start, rather than in a constructor or
     * `init` block. §5.1 requires re-wiring whenever the service is recreated, and
     * a code path that *cannot* skip the wiring beats one that must remember to.
     *
     * There is deliberately no DNS call: libXray v26.7.11 has no `setDNS`. §5.2 is
     * satisfied entirely by the config's `dns` block and
     * `VpnService.Builder.addDnsServer()`.
     */
    public suspend fun start(
        configFile: File,
        protector: SocketProtector,
    ) {
        withContext(io) {
            // gomobile maps Go's int to a Java long; VpnService.protect() takes an
            // int, so the bridge narrows here. The Go wrapper discards the returned
            // boolean, so a false does not abort the dial — it surfaces only as
            // §5.1's symptom. SocketProtector's implementation must log it.
            val controller = DialerController { fd -> protector.protect(fd.toInt()) }
            LibXray.registerDialerController(controller)
            LibXray.registerListenerController(controller)

            LibXrayInvoke.call("runXray", JSONObject().put("configPath", configFile.absolutePath))
        }
    }

    /** True when the core reports itself running. §5.5 — never infer this locally. */
    public suspend fun isRunning(): Boolean =
        withContext(io) {
            LibXrayInvoke.call("getXrayState")?.optBoolean("running", false) ?: false
        }

    /**
     * Idempotent — teardown runs from disconnect, onRevoke, and onDestroy (§5.4).
     *
     * The broad catch here is the one permitted in this milestone: §5.4 requires
     * teardown to complete even when a step fails, because a leaked fd wedges the
     * VPN subsystem until reboot. Stopping an already-stopped core is not an error
     * worth propagating.
     */
    @Suppress("SwallowedException")
    public suspend fun stop() {
        withContext(io) {
            try {
                LibXrayInvoke.call("stopXray")
            } catch (e: XrayException) {
                // Deliberately swallowed — see the KDoc above. Not logged, because
                // the message can quote the config (§5.6) and the caller already
                // publishes a state transition.
            }
        }
    }
}
