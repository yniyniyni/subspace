// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

/**
 * How many UI holders currently want [TunnelClient] bound, so only the first
 * [acquire] binds and only the last [release] unbinds.
 *
 * ARCHITECTURE.md §5.5: a task-clearing relaunch of `MainActivity` runs the new
 * instance's `onStart` before the old instance's `onStop`. `TunnelClient` has one
 * `ServiceConnection`, so a bare bind/unbind pair let the old instance's unbind drop
 * the binding the new instance relied on, and Home read "Disconnected" while the
 * tunnel was up (Pixel 8, 2026-10-03).
 */
internal class BindRefCount {
    private var holders = 0

    /** Records a holder; true when this is the first, so the caller should bind. */
    @Synchronized
    fun acquire(): Boolean = holders++ == 0

    /** Drops a holder; true when it was the last, so the caller should unbind. */
    @Synchronized
    fun release(): Boolean {
        if (holders == 0) return false
        holders--
        return holders == 0
    }
}
