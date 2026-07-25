// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

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

    /** Idempotent. Safe to call when nothing is running (§5.4). */
    fun stop(): Unit = nativeStop()

    val isRunning: Boolean
        get() = nativeIsRunning()
}

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
      task-stack-size: 20480
      log-level: warn
    """.trimIndent()
