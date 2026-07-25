// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.util.Log
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.StartupStage
import art.yniyniyni.subspace.core.model.failure
import art.yniyniyni.subspace.core.xray.SocketProtector
import art.yniyniyni.subspace.core.xray.TunnelSettings
import art.yniyniyni.subspace.core.xray.XrayConfigGenerator
import art.yniyniyni.subspace.core.xray.XrayController
import art.yniyniyni.subspace.core.xray.XrayException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "TunnelService"

private const val TUN_ADDRESS = "10.7.0.1"
private const val TUN_PREFIX = 30
private const val TUN_MTU = 8500
private const val DNS_SERVER = "1.1.1.1"

/**
 * Owns the tunnel.
 *
 * Runs in `:bg` (§3). Nothing here may be reached from `:main` except through
 * [ITunnelService] — the processes share no memory, so a Hilt singleton or an
 * `object` is two different instances.
 */
// A VpnService carries a fixed lifecycle surface (onCreate/onRevoke/onDestroy/
// onBind) on top of the start sequence, teardown, and state publication. The
// count is inherent, and splitting the class would put §5.1's protector wiring
// and §5.4's teardown in two files — §10.2 warns specifically against that kind
// of tidy-up here.
@Suppress("TooManyFunctions")
class TunnelService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callbacks = RemoteCallbackList<ITunnelCallback>()

    private var controller: XrayController? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var configFile: File? = null
    private var startJob: Job? = null

    /**
     * §5.5: the single source of truth. `:main` caches it, never derives it.
     *
     * Named `currentState`, not `state`: the AIDL stub declares its own
     * `getState()`, which shadows a property of that name inside the binder
     * object and resolves to the wrong type.
     */
    @Volatile
    private var currentState: ConnectionState = ConnectionState.Disconnected

    override fun onCreate() {
        super.onCreate()
        TunnelNotification.ensureChannel(this)
    }

    // ── State publication ───────────────────────────────────────────────────

    private fun publish(next: ConnectionState) {
        currentState = next
        val parcel = ConnectionStateParcel.from(next)
        val count = callbacks.beginBroadcast()
        repeat(count) { i ->
            try {
                callbacks.getBroadcastItem(i).onStateChanged(parcel)
            } catch (e: android.os.RemoteException) {
                // The UI process died mid-broadcast. RemoteCallbackList already
                // prunes dead entries; nothing here should abort the tunnel, and
                // §5.6 forbids logging anything that might quote the config.
                Log.w(TAG, "callback dropped: ${e.javaClass.simpleName}")
            }
        }
        callbacks.finishBroadcast()
    }

    // ── Start sequence ──────────────────────────────────────────────────────

    /**
     * §5.3: the whole sequence is on IO, so the connect button stays live.
     * §10.4: no broad catch — every step publishes its own specific failure and
     * unwinds what it already built. A swallowed failure here produces the worst
     * state this app can reach: UI says connected, nothing works, no log line.
     */
    private fun startTunnel(profile: Profile) {
        startJob?.cancel()
        startJob =
            scope.launch {
                val xray = XrayController()
                controller = xray

                // Split at the seam that matters for unwinding: once the core is
                // up, every later failure must stop it again.
                val socksPort = startCore(xray, profile) ?: return@launch
                attachTun(xray, socksPort)
            }
    }

    /**
     * Allocates a port, generates and validates the config, and starts libXray.
     *
     * @return the loopback SOCKS port, or null if a step failed — in which case
     *   the specific failure has already been published and nothing is running.
     */
    // One early return per step is the point, not a smell: §10.4 requires each
    // stage of the start sequence to fail specifically and stop there. Folding
    // these into a single exit — via a Result chain or a nested else-ladder —
    // would blur exactly the attribution the StartupStage enum exists to give.
    @Suppress("ReturnCount")
    private suspend fun startCore(
        xray: XrayController,
        profile: Profile,
    ): Int? {
        publish(ConnectionState.Connecting(StartupStage.AllocatingPort))
        val socksPort =
            try {
                xray.allocatePort()
            } catch (e: XrayException) {
                fail(FailureReason.PortAllocationFailed, e)
                return null
            }

        publish(ConnectionState.Connecting(StartupStage.GeneratingConfig))
        val settings = TunnelSettings(socksPort, DNS_SERVER, enableSniffing = true)
        val file =
            try {
                writeConfig(XrayConfigGenerator.generate(profile, settings))
            } catch (e: java.io.IOException) {
                fail(FailureReason.ConfigGenerationFailed, e)
                return null
            }
        configFile = file

        // §6: validate before starting. libXray's testXray takes a path, so the
        // bytes validated are exactly the bytes runXray will read.
        publish(ConnectionState.Connecting(StartupStage.ValidatingConfig))
        try {
            xray.validate(file)
        } catch (e: XrayException) {
            fail(FailureReason.ConfigRejected, e)
            return null
        }

        publish(ConnectionState.Connecting(StartupStage.StartingCore))
        try {
            xray.start(file, protector())
        } catch (e: XrayException) {
            fail(FailureReason.CoreStartFailed, e)
            return null
        }

        return socksPort
    }

    /**
     * Builds the TUN interface and hands its fd to tun2socks.
     *
     * Every failure here stops the core that [startCore] left running — §5.4: a
     * half-started tunnel must not survive as a leaked fd plus a live Go runtime.
     */
    private suspend fun attachTun(
        xray: XrayController,
        socksPort: Int,
    ) {
        publish(ConnectionState.Connecting(StartupStage.EstablishingTun))
        val fd = establishTun()
        if (fd == null) {
            xray.stop()
            fail(
                FailureReason.TunEstablishFailed,
                IllegalStateException("VpnService.Builder.establish() returned null"),
            )
            return
        }
        tunInterface = fd

        publish(ConnectionState.Connecting(StartupStage.StartingTunnel))
        val config = tun2socksConfig(socksPort = socksPort, mtu = TUN_MTU)
        // Tun2Socks.start refuses a bad fd rather than aborting the process; it
        // returning false is a real start failure, never something to ignore.
        if (!Tun2Socks.start(config, fd.fd)) {
            xray.stop()
            fd.close()
            tunInterface = null
            fail(
                FailureReason.TunnelStartFailed,
                IllegalStateException("tun2socks refused to start"),
            )
            return
        }

        goForeground()

        publish(ConnectionState.Connected(System.currentTimeMillis(), socksPort))
    }

    /**
     * §9: a foreground service with an ongoing notification is mandatory for the
     * whole life of the tunnel.
     *
     * The typed overload is API 29+, and §14.1's `systemExempted` only becomes
     * meaningful on API 34, so below Q the untyped call is both the only option
     * and the correct one. minSdk stays at 26 rather than being raised to make
     * this line shorter.
     */
    private fun goForeground() {
        val notification =
            TunnelNotification.build(this, getString(R.string.notification_connected))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                TunnelNotification.ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
        } else {
            startForeground(TunnelNotification.ID, notification)
        }
    }

    private fun fail(
        reason: FailureReason,
        cause: Exception,
    ) {
        // failure() redacts at construction — libXray's errors quote the config
        // straight back (§5.6).
        publish(failure(reason, cause.message.orEmpty()))
    }

    /**
     * §5.1. Wired on every start, never cached in a field or a constructor:
     * libXray's dialer controller is process-global state in the Go runtime, so a
     * reference to a dead `VpnService` keeps it alive and silently stops
     * protecting.
     */
    private fun protector() =
        SocketProtector { fd ->
            val ok = protect(fd)
            if (!ok) {
                // libXray DISCARDS this result (see docs/agent/research/libxray-api.md
                // §2), so nothing upstream reports it. Without this line a failed
                // protect is completely invisible and presents only as §5.1's
                // symptom: connected, no traffic, rising CPU.
                Log.e(TAG, "VpnService.protect() failed — traffic will loop back into the tunnel")
            }
            ok
        }

    private fun writeConfig(json: String): File {
        // Internal storage, not cache: §5.6 — the config holds the UUID and
        // REALITY key, and cache is more readily harvested.
        val file = File(filesDir, "xray-config.json")
        file.writeText(json)
        return file
    }

    /** §5.2, half two. The `dns` block in the generated config is half one. */
    private fun establishTun(): ParcelFileDescriptor? {
        val builder =
            Builder()
                .setSession(getString(R.string.tunnel_session_name))
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS_SERVER)

        // §8: the app must never be routed through itself. Caught per package
        // because a stale entry must not abort the whole tunnel — M5 adds the
        // user's list to this same path.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "could not exclude own package: ${e.javaClass.simpleName}")
        }

        return builder.establish()
    }

    // ── Teardown ────────────────────────────────────────────────────────────

    /**
     * Idempotent, and reachable from three unsynchronised places (§5.4):
     * [disconnect], [onRevoke], and [onDestroy].
     *
     * A leaked fd here wedges the VPN subsystem until reboot, so every step runs
     * even if an earlier one failed.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun stopTunnel(finalState: ConnectionState) {
        startJob?.cancel()
        startJob = null
        publish(ConnectionState.Disconnecting)

        // Order matters: stop feeding packets in before tearing the core down.
        try {
            Tun2Socks.stop()
        } catch (e: Throwable) {
            // Includes NoClassDefFoundError if System.loadLibrary failed — §5.4
            // says teardown must still finish, so this cannot be allowed to
            // abandon the fd below.
            Log.e(TAG, "tun2socks stop failed: ${e.javaClass.simpleName}")
        }

        try {
            tunInterface?.close()
        } catch (e: java.io.IOException) {
            Log.e(TAG, "closing tun fd failed: ${e.javaClass.simpleName}")
        }
        tunInterface = null

        // stopBlocking(), not stop(): onDestroy has no scope that outlives it,
        // and §5.4 requires teardown to finish before the process dies. See the
        // KDoc on XrayController.stopBlocking for why that beats runBlocking.
        controller?.stopBlocking()
        controller = null

        configFile?.delete()
        configFile = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        publish(finalState)
    }

    /**
     * §5.4: the system calls this when another VPN app takes over or the user
     * revokes permission.
     */
    override fun onRevoke() {
        stopTunnel(failure(FailureReason.Revoked, "VPN permission revoked"))
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel(ConnectionState.Disconnected)
        callbacks.kill()
        scope.cancel()
        super.onDestroy()
    }

    // ── IPC ─────────────────────────────────────────────────────────────────

    private val binder =
        object : ITunnelService.Stub() {
            override fun connect(profile: ProfileParcel) {
                startTunnel(profile.toProfile())
            }

            override fun disconnect() {
                stopTunnel(ConnectionState.Disconnected)
            }

            override fun getState(): ConnectionStateParcel = ConnectionStateParcel.from(currentState)

            override fun registerCallback(callback: ITunnelCallback) {
                callbacks.register(callback)
            }

            override fun unregisterCallback(callback: ITunnelCallback) {
                callbacks.unregister(callback)
            }
        }

    /**
     * The system binds with [SERVICE_INTERFACE] for always-on VPN and must get
     * `VpnService`'s own binder; the app gets ours. Getting this backwards breaks
     * always-on in a way that only shows up after a reboot.
     */
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else binder
}
