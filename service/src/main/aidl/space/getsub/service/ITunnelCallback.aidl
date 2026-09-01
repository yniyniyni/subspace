// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service;

import space.getsub.service.ConnectionStateParcel;

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
