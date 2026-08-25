// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

internal sealed interface InitialForegroundOutcome {
    data object Started : InitialForegroundOutcome

    data object Rejected : InitialForegroundOutcome
}

/** Starts slow tunnel work only after the foreground-service contract is established. */
internal inline fun runAfterForegroundEstablished(
    establishForeground: () -> Boolean,
    onRejected: () -> Unit,
    launchStartup: () -> Unit,
): InitialForegroundOutcome =
    if (establishForeground()) {
        launchStartup()
        InitialForegroundOutcome.Started
    } else {
        onRejected()
        InitialForegroundOutcome.Rejected
    }
