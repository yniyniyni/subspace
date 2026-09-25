// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

/**
 * Bridge to hev-socks5-tunnel.
 *
 * Translates the raw IP packets the OS hands us on the TUN fd into SOCKS5
 * connections against the loopback inbound — the middle hop of the packet path
 * in ARCHITECTURE.md §3.
 *
 * §10.2: load-bearing, not boilerplate. Do not refactor for elegance.
 */
internal object Tun2Socks {
    init {
        System.loadLibrary("tun2socks")
    }

    @JvmStatic
    private external fun nativeStart(
        config: String,
        tunFd: Int,
    ): Boolean

    @JvmStatic
    private external fun nativeStop()

    @JvmStatic
    private external fun nativeIsRunning(): Boolean

    @JvmStatic
    private external fun nativeStats(): LongArray?

    /**
     * Starts the tunnel on a background thread owned by the native side.
     *
     * @return false if a tunnel is already running or the thread could not start.
     *   The caller must treat false as a start-sequence failure (§10.4) — never
     *   publish Connected after it.
     */
    fun start(
        config: String,
        tunFd: Int,
    ): Boolean = nativeStart(config, tunFd)

    /**
     * Idempotent, and safe under the concurrent teardown §5.4 describes.
     *
     * If `System.loadLibrary` failed, touching this object throws
     *    `NoClassDefFoundError`, including from `onDestroy`. §5.4 requires
     *    teardown to finish anyway, so guard the call there.
     */
    fun stop(): Unit = nativeStop()

    val isRunning: Boolean
        get() = nativeIsRunning()

    /**
     * A raw counter reading, or null when no tunnel is running.
     *
     * **This is the boundary where hev's `tx`/`rx` become uplink/downlink**
     * (spec §1.2) — `tx` is a read *from* the TUN and is therefore uplink. No
     * caller above this line may use `tx`/`rx` again.
     *
     * The values are cumulative since tunnel start and wrap at 4 GiB on 32-bit
     * ABIs, so no caller may render them directly: `TrafficSampler` turns them
     * into wrap-safe deltas (spec §1.3).
     */
    fun stats(): TunnelCounters? =
        nativeStats()?.let { v ->
            TunnelCounters(
                uplinkBytes = v[0],
                downlinkBytes = v[1],
                uplinkPackets = v[2],
                downlinkPackets = v[3],
            )
        }
}

/** One raw reading from hev. Cumulative, and 32-bit-wrappable — see [Tun2Socks.stats]. */
internal data class TunnelCounters(
    val uplinkBytes: Long,
    val downlinkBytes: Long,
    val uplinkPackets: Long,
    val downlinkPackets: Long,
)

/**
 * hev-socks5-tunnel is configured with YAML, not arguments.
 *
 * Passed as a string rather than a file: upstream's
 * `hev_socks5_tunnel_main_from_str` accepts one directly, which avoids writing a
 * second config to disk and keeps the SOCKS port out of the filesystem.
 *
 * [socksPort] is the dynamically allocated loopback port from
 * `XrayController.allocatePort()` — §10.6 forbids a literal here.
 *
 * Every key below was checked against `third_party/hev-socks5-tunnel/conf/main.yml`.
 * `tunnel.name`, `ipv4`, and `ipv6` are deliberately absent: the interface
 * already exists — we hand the tunnel an fd that `VpnService.Builder` created and
 * configured — so letting this file restate the addressing would be a second
 * source of truth for something §5.2 depends on.
 */
internal fun tun2socksConfig(
    socksPort: Int,
    mtu: Int,
): String =
    """
    tunnel:
      mtu: $mtu
    socks5:
      port: $socksPort
      address: 127.0.0.1
      udp: 'udp'
    misc:
      log-level: warn
    """.trimIndent()
