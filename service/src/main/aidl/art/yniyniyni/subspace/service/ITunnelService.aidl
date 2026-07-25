// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service;

import art.yniyniyni.subspace.service.ConnectionStateParcel;
import art.yniyniyni.subspace.service.ProfileParcel;
import art.yniyniyni.subspace.service.ITunnelCallback;

/**
 * The only channel between :main and :bg.
 *
 * ARCHITECTURE.md §3: the two processes share no memory. Hilt singletons,
 * `object` declarations, and static fields exist twice, so anything that must be
 * consistent crosses here or through Room — never through a shared reference.
 */
interface ITunnelService {
    void connect(in ProfileParcel profile);

    void disconnect();

    /**
     * §5.5: after process death the UI must rebind and re-read actual state.
     * This is that call. The UI never infers connection state locally — an app
     * showing "Disconnected" while the tunnel is up is worse than one that
     * crashes.
     */
    ConnectionStateParcel getState();

    void registerCallback(ITunnelCallback callback);

    void unregisterCallback(ITunnelCallback callback);
}
