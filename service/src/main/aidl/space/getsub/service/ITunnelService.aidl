// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service;

import space.getsub.service.ConnectionStateParcel;
import space.getsub.service.ITunnelCallback;
import space.getsub.service.ILatencyCallback;
import space.getsub.service.LatencyOptionsParcel;

/**
 * The only channel between :main and :bg.
 *
 * ARCHITECTURE.md §3: the two processes share no memory. Hilt singletons,
 * `object` declarations, and static fields exist twice, so anything that must be
 * consistent crosses here or through Room — never through a shared reference.
 */
interface ITunnelService {
    /**
     * `oneway` deliberately. The Binder method only enqueues into the service's
     * serial coordinator; teardown joins the tun2socks worker and stops the Go
     * core from that consumer, never from the caller's UI thread (§5.3).
     */
    oneway void disconnect();

    /**
     * Rebuilds the tunnel so a changed per-app selection takes effect.
     *
     * `VpnService.Builder`'s allow/deny calls apply at establish() time only, so a
     * live session keeps whatever selection it was built with (ARCHITECTURE.md §8).
     * This restarts the current session with the profile :bg already holds — the UI
     * does not re-supply one, because §5.5 makes the service the source of truth for
     * what is connected.
     *
     * A no-op when nothing is connected. `oneway` for the reason disconnect() is: it
     * performs a full teardown, and charging that to the caller's UI thread is the
     * freeze §5.3 forbids.
     */
    oneway void reapplyPerApp();

    /**
     * §5.5: after process death the UI must rebind and re-read actual state.
     * This is that call. The UI never infers connection state locally — an app
     * showing "Disconnected" while the tunnel is up is worse than one that
     * crashes.
     */
    ConnectionStateParcel getState();

    void registerCallback(ITunnelCallback callback);

    void unregisterCallback(ITunnelCallback callback);

    /**
     * Measures the latency of every profile in `profileIds`, reporting each
     * result on `callback` as it lands.
     *
     * Measurement lives here rather than in :main because §5.1's protector is
     * here: a ping opens a socket to the remote server, and while a session is up
     * an unprotected one is routed back into the TUN — so it would time the
     * server *through* the tunnel instead of timing the server.
     *
     * `oneway` for the reason disconnect() is: a measurement blocks for up to the
     * timeout, and a synchronous binder call would charge that to the caller's UI
     * thread (§5.3).
     *
     * Ids, not ProfileParcels: :bg already has Room access, and marshalling forty
     * profiles into one transaction runs at binder's 1 MB ceiling for no benefit.
     *
     * Starting a run supersedes any run already in flight.
     */
    oneway void startLatencyRun(long runId, in long[] profileIds, in int[] modes,
                                in LatencyOptionsParcel options, ILatencyCallback callback);

    /**
     * Stops scheduling for `runId`.
     *
     * Not instantaneous, and callers must not imply it is: a measurement already
     * inside libXray's blocking ping runs to completion. Its result is discarded
     * by run id rather than delivered.
     */
    oneway void cancelLatencyRun(long runId);
}
