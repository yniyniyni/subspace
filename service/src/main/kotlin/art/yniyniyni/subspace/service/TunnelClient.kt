// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.Profile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TunnelClient"

/**
 * `:main`'s handle on the tunnel.
 *
 * ARCHITECTURE.md §5.5: [state] is a **cache** of what the service reported, never
 * a source. It is refreshed from `getState()` on every bind, because after
 * process death this side's idea of the world is worthless — and an app showing
 * "Disconnected" while the tunnel is up is worse than one that crashes.
 *
 * A `@Singleton` here is safe precisely because it lives in `:main` only. §3's
 * warning is about expecting `:bg` to see it — it will not; that is what the
 * binder is for.
 */
@Singleton
public class TunnelClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    public val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var service: ITunnelService? = null

    private val callback =
        object : ITunnelCallback.Stub() {
            override fun onStateChanged(state: ConnectionStateParcel) {
                _state.value = state.toState()
            }
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                val svc = ITunnelService.Stub.asInterface(binder)
                service = svc
                try {
                    svc.registerCallback(callback)
                    // §5.5: re-read the real state on every bind.
                    _state.value = svc.state.toState()
                } catch (e: android.os.RemoteException) {
                    Log.w(TAG, "bind handshake failed: ${e.javaClass.simpleName}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                // Deliberately NOT Disconnected: :bg died, which says nothing
                // about whether the tunnel is down. Claiming Disconnected here
                // would be §5.5's lying UI. Rebinding re-reads the truth.
                _state.value = ConnectionState.Disconnecting
            }
        }

    public fun bind() {
        context.bindService(
            Intent(context, TunnelService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    public fun unbind() {
        try {
            service?.unregisterCallback(callback)
        } catch (e: android.os.RemoteException) {
            Log.w(TAG, "unregister failed: ${e.javaClass.simpleName}")
        }
        service = null
        runCatching { context.unbindService(connection) }
    }

    /**
     * §9: started, not just bound. A bound-only service dies with the last
     * unbind — which is the UI going to background — taking the tunnel with
     * it. `TunnelService` calls `startForeground` immediately on connect so
     * the start window the platform allows is never missed.
     *
     * @param rowId the Room primary key of the profile being connected.
     *   Threaded straight into [ProfileParcel.from] rather than defaulted, so
     *   a caller cannot forget it: [ProfileParcel]'s own KDoc explains why
     *   [ProfileParcel.UNASSIGNED_ROW_ID] makes `:bg`'s connect-outcome
     *   write-back (`ProfileRepository.recordConnected`/`recordError`) a
     *   silent no-op — closing that gap is the point of this parameter.
     */
    public fun connect(
        profile: Profile,
        rowId: Long,
    ) {
        context.startForegroundService(Intent(context, TunnelService::class.java))
        try {
            service?.connect(ProfileParcel.from(profile, rowId))
        } catch (e: android.os.RemoteException) {
            Log.e(TAG, "connect failed: ${e.javaClass.simpleName}")
        }
    }

    public fun disconnect() {
        try {
            service?.disconnect()
        } catch (e: android.os.RemoteException) {
            Log.e(TAG, "disconnect failed: ${e.javaClass.simpleName}")
        }
    }
}
