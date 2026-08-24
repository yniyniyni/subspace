// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import kotlinx.coroutines.CancellationException

/**
 * Runs one app-foreground refresh cycle without letting storage or scheduling failures escape the
 * application scope. Both operations are attempted in order, and genuine coroutine cancellation
 * is rethrown only after the reschedule attempt has completed.
 *
 * [reportFailure] receives only the exception's class name. Passing the [Exception] itself would
 * let a caller accidentally expose its message, which can contain profile fields or URLs (§5.6).
 */
internal suspend fun runForegroundRefresh(
    refresh: suspend () -> Unit,
    reschedule: suspend () -> Unit,
    reportFailure: (String) -> Unit,
) {
    val refreshCancellation = runForegroundStep(refresh, reportFailure)
    val rescheduleCancellation = runForegroundStep(reschedule, reportFailure)

    if (refreshCancellation != null) {
        if (rescheduleCancellation != null && rescheduleCancellation !== refreshCancellation) {
            refreshCancellation.addSuppressed(rescheduleCancellation)
        }
        throw refreshCancellation
    }
    if (rescheduleCancellation != null) throw rescheduleCancellation
}

@Suppress("TooGenericExceptionCaught") // Application-scope containment is this boundary's contract.
private suspend fun runForegroundStep(
    operation: suspend () -> Unit,
    reportFailure: (String) -> Unit,
): CancellationException? =
    try {
        operation()
        null
    } catch (cancellation: CancellationException) {
        cancellation
    } catch (error: Exception) {
        reportFailure(error.javaClass.name)
        null
    }
