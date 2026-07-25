// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service;

import art.yniyniyni.subspace.service.ConnectionStateParcel;

/**
 * Pushes tunnel state from :bg to :main.
 *
 * `oneway` is deliberate: a blocking callback would let a slow or wedged UI
 * stall the tunnel, and ARCHITECTURE.md §5.3 requires the service to stay
 * responsive through the whole start sequence.
 */
oneway interface ITunnelCallback {
    void onStateChanged(in ConnectionStateParcel state);
}
