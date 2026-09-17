// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import space.getsub.core.data.SettingsRepository
import javax.inject.Inject

private const val TAG = "BootReceiver"

/**
 * Spec §4.2: connect at boot when the user asked for it.
 *
 * Redundant for anyone using always-on VPN — the platform starts the service
 * itself there — and it exists for the user who wants boot start without
 * granting always-on.
 *
 * Carries no config and no profile bytes (§5.6): it writes session intent and
 * lets `TunnelService` reconcile, exactly as always-on and a sticky restart do.
 */
@AndroidEntryPoint
internal class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        // §5.3: Room reads do not go on the main thread, and a receiver's main
        // thread is the one the system is waiting on.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                connectIfAsked(context)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun connectIfAsked(context: Context) {
        // prepare() returns null when consent already exists. There is no
        // Activity at boot to show the dialog, so a non-null answer means give
        // up quietly rather than nag.
        val consentGranted = VpnService.prepare(context) == null
        val action =
            bootAction(
                autostartEnabled = settingsRepository.bootAutostart.first(),
                vpnConsentGranted = consentGranted,
                activeProfileRowId = settingsRepository.activeProfileIdNow(),
            )
        if (action == BootAction.DoNothing) {
            Log.i(TAG, "boot autostart: nothing to do")
            return
        }
        settingsRepository.setTunnelSessionWanted(true)
        context.startForegroundService(Intent(context, TunnelService::class.java))
    }
}
