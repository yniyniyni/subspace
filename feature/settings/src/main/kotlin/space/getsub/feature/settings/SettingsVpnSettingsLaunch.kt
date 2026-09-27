// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.lifecycle.Lifecycle

/**
 * Controller ruling R14 (M8.5 spec §6 #10, final fix wave finding #1): the deferred
 * `ACTION_VPN_SETTINGS` deep link fires only while the Settings screen is RESUMED, never while
 * it is merely requested — see the `LaunchedEffect` in [TunnelSection] for why that distinction
 * matters. The confirm path on the battery-optimisation prompt starts the battery settings list
 * on top of this screen, which moves it off RESUMED without stopping it (Compose keeps
 * composing frames until `ON_STOP`); gating on RESUMED rather than on the request alone is what
 * stops the deep link from launching a frame later and burying that battery screen under
 * `ACTION_VPN_SETTINGS`.
 *
 * Extracted as a pure function — rather than inlined in the `LaunchedEffect` — so the four cases
 * (not requested; requested but paused, stopped, or created; requested and resumed) have a JVM
 * test that needs no Compose runtime. Kept in its own file, rather than alongside
 * [SettingsScreen], because that file already sits at detekt's `TooManyFunctions` threshold.
 */
internal fun shouldLaunchVpnSettings(requested: Boolean, lifecycleState: Lifecycle.State): Boolean =
    requested && lifecycleState == Lifecycle.State.RESUMED
