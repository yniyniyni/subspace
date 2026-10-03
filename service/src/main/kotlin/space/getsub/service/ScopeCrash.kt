// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.failure

/**
 * What a crash on the service scope may publish (M8.5 spec §4.4; the M8 review's M4b).
 *
 * Nothing while the tunnel still runs: publishing `Failed` there is item #4 —
 * Home reading `Failed` while traffic flows. Nothing over an existing `Failed`:
 * a Room failure in `onRevoke`'s launched intent-clear lands here, and
 * overwriting `Failed(Revoked)` loses the one fact M8's ARCHITECTURE.md §11 row 7 checks for.
 * Otherwise the crash is reported, as before. This never tears anything down:
 * M8 reverted exactly that (R58/R60).
 */
internal fun scopeCrashPublication(
    current: ConnectionState,
    tunnelRunning: Boolean,
    errorClass: String,
): ConnectionState? =
    when {
        current is ConnectionState.Failed -> null
        tunnelRunning -> null
        else -> failure(FailureReason.CoreStartFailed, errorClass)
    }
