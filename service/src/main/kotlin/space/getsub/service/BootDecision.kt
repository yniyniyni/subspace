// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// File groups the boot decision's type and its one pure function together,
// not just BootAction — see BootReceiver.kt, which is what actually consumes
// both.
@file:Suppress("MatchingDeclarationName", "Filename")

package space.getsub.service

/** What [BootReceiver] should do. */
internal enum class BootAction { Connect, DoNothing }

/**
 * Spec §4.2.
 *
 * Note what is **not** a parameter: whether the session was wanted before the
 * device went down. Boot autostart connects on every boot when enabled — the
 * setting is the intent for this path, and this function writes it rather than
 * reading it.
 */
internal fun bootAction(
    autostartEnabled: Boolean,
    vpnConsentGranted: Boolean,
    activeProfileRowId: Long?,
): BootAction =
    if (autostartEnabled && vpnConsentGranted && activeProfileRowId != null) {
        BootAction.Connect
    } else {
        BootAction.DoNothing
    }
