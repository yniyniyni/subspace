// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Messenger
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.LatencyOptions
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.LatencyTarget
import space.getsub.core.model.PingMode
import space.getsub.core.model.Profile
import space.getsub.core.model.TrafficSample
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TunnelClient"
internal const val ACTION_CONNECT = "space.getsub.service.action.CONNECT"
internal const val ACTION_DISCONNECT = "space.getsub.service.action.DISCONNECT"
internal const val EXTRA_PROFILE = "space.getsub.service.extra.PROFILE"
internal const val EXTRA_TEST_CONNECT_OBSERVER =
    "space.getsub.service.extra.TEST_CONNECT_OBSERVER"
internal const val TEST_CONNECT_RECEIVED = 1

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
public class TunnelClient private constructor(
    @ApplicationContext private val context: Context,
    private val testObservation: TestObservation?,
) {
    private data class TestObservation(val messenger: Messenger)

    @Inject
    public constructor(
        @ApplicationContext context: Context,
    ) : this(context, null)

    /** Debug-instrumentation seam; release callers cannot supply an observer. */
    internal constructor(
        context: Context,
        testConnectObserver: Messenger,
    ) : this(context, TestObservation(testConnectObserver))

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    public val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _traffic = MutableStateFlow<TrafficSample?>(null)

    /**
     * Null until the current session reports its first sample, and null again
     * once that session ends — the same going-stale cache [state] documents
     * (§5.5): a traffic total outliving its session is that bug with a
     * different field.
     */
    public val traffic: StateFlow<TrafficSample?> = _traffic.asStateFlow()

    /**
     * Whether the Binder handshake has completed since the latest [bind].
     *
     * [state] is a **cache**: it is a plain field, so once nothing calls [unbind]'s
     * `_state.value = ...` again, it holds whatever it last held forever — including across
     * an app backgrounding that outlives the tunnel itself (§9's `onRevoke()`, `:bg` killed).
     * Neither of those can notify a client that has unregistered its callback, so [state] alone
     * cannot answer "is this still true"; only [isBound] can, and only by admitting when it
     * cannot answer at all. It stays false while [Context.bindService] has merely accepted a
     * pending request and becomes true only after [ServiceConnection.onServiceConnected]
     * registers [callback] and re-reads the real service state. Otherwise the foreground refresh
     * that starts alongside an activity bind could consume the previous session's stale proxy
     * port before the asynchronous handshake replaces it. This is §5.5's "declining to guess"
     * applied to a cache that has gone stale rather than one that was never populated.
     *
     * `@Volatile` because [bind]/[unbind] run on the main thread (Activity lifecycle callbacks)
     * while a background worker's read of this can run on [kotlinx.coroutines.Dispatchers.IO].
     */
    @Volatile
    public var isBound: Boolean = false
        private set

    private var service: ITunnelService? = null

    private val callback =
        object : ITunnelCallback.Stub() {
            override fun onStateChanged(state: ConnectionStateParcel) {
                _state.value = state.toState()
            }

            override fun onTrafficSample(sample: TrafficSampleParcel) {
                _traffic.value = sample.toSample()
            }
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                val svc = ITunnelService.Stub.asInterface(binder)
                try {
                    svc.registerCallback(callback)
                    // §5.5: re-read the real state on every bind.
                    _state.value = svc.state.toState()
                    service = svc
                    // Publish this last. A worker that observes true must also
                    // observe the refreshed state above, never the stale cache.
                    isBound = true
                } catch (e: android.os.RemoteException) {
                    isBound = false
                    service = null
                    runCatching { svc.unregisterCallback(callback) }
                    Log.w(TAG, "bind handshake failed: ${e.javaClass.simpleName}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                isBound = false
                service = null
                // Deliberately NOT Disconnected: :bg died, which says nothing
                // about whether the tunnel is down. Claiming Disconnected here
                // would be §5.5's lying UI. Rebinding re-reads the truth.
                _state.value = ConnectionState.Disconnecting
                // :bg died — its own session is what [traffic] tracked, and
                // that session's fate is now unknown. A stale total surviving
                // this is the same cache-gone-stale bug [state] avoids above.
                _traffic.value = null
            }
        }

    /** Starts a bind attempt. [isBound] remains false until its handshake completes. */
    public fun bind() {
        isBound = false
        context.bindService(
            Intent(context, TunnelService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    /**
     * [isBound] flips false first, deliberately before the unregister/unbind calls below: those
     * can fail ([android.os.RemoteException], a stale [ServiceConnection]) without changing the
     * one fact that matters to a caller of [isBound] — this client no longer has, or is trying to
     * keep, a live link to the service.
     */
    public fun unbind() {
        isBound = false
        try {
            service?.unregisterCallback(callback)
        } catch (e: android.os.RemoteException) {
            Log.w(TAG, "unregister failed: ${e.javaClass.simpleName}")
        }
        service = null
        runCatching { context.unbindService(connection) }
        // This client no longer receives samples for whatever session is or
        // isn't live — the same reasoning as onServiceDisconnected's clear.
        _traffic.value = null
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
        val request =
            Intent(context, TunnelService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_PROFILE, ProfileParcel.from(profile, rowId))
        testObservation?.let { request.putExtra(EXTRA_TEST_CONNECT_OBSERVER, it.messenger) }
        context.startForegroundService(request)
    }

    public fun disconnect() {
        try {
            service?.disconnect()
        } catch (e: android.os.RemoteException) {
            Log.e(TAG, "disconnect failed: ${e.javaClass.simpleName}")
        }
    }

    /** §8: rebuild the tunnel so a changed per-app selection takes effect. No-op when disconnected. */
    public fun reapplyPerApp() {
        try {
            service?.reapplyPerApp()
        } catch (e: android.os.RemoteException) {
            Log.e(TAG, "reapplyPerApp failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Held so the binder callback is not collected mid-run.
     *
     * `ILatencyCallback.Stub` is a strong reference only from here; a local would
     * become unreachable as soon as [startLatencyRun] returned, and the results
     * would stop arriving somewhere between the first and last row.
     */
    private var latencyCallback: ILatencyCallback.Stub? = null

    /**
     * Measures [profileIds] in `:bg`, reporting each result as it lands.
     *
     * Deliberately does **not** call `startForegroundService`, unlike [connect]:
     * a measurement must not outlive the UI that asked for it, and it needs no
     * TUN, no VPN permission and no notification. Binding alone is enough —
     * `TunnelService.onBind` hands out the AIDL binder for any action other than
     * `SERVICE_INTERFACE`.
     *
     * If nothing is bound yet the run is dropped and [onFinished] still fires, so
     * the caller's rows return to idle instead of sitting on "testing" forever.
     */
    /**
     * @return false when nothing was bound and the run was dropped. Callers that
     *   spent a once-per-session claim on it need to know, or that claim is burnt
     *   on a measurement which never happened.
     */
    public fun startLatencyRun(
        runId: Long,
        targets: List<LatencyTarget>,
        options: LatencyOptions,
        onResult: (Long, LatencyResult) -> Unit,
        onFinished: () -> Unit,
    ): Boolean {
        val stub =
            object : ILatencyCallback.Stub() {
                override fun onResult(
                    id: Long,
                    profileId: Long,
                    delayMillis: Int,
                    outcome: Int,
                ) {
                    // Second fence, after :bg's own. A result stamped with a
                    // superseded run must not overwrite a row the current run is
                    // retesting — and an ordinal from a future version degrades to
                    // a failure rather than throwing on an out-of-range index.
                    if (id != runId) return
                    val resolved = LatencyOutcome.entries.getOrNull(outcome) ?: LatencyOutcome.UNREACHABLE
                    onResult(profileId, LatencyResult(delayMillis, resolved))
                }

                override fun onFinished(id: Long) {
                    if (id == runId) onFinished()
                }
            }
        latencyCallback = stub
        val bound = service
        // Two ways a run never reaches :bg — nothing bound yet, or the binder died
        // between the check and the call — and both must report the same thing to
        // the caller, since both leave a once-per-session claim spent on a
        // measurement that did not happen.
        val started =
            if (bound == null) {
                Log.w(TAG, "latency run dropped: not bound")
                false
            } else {
                dispatchLatencyRun(bound, runId, targets, options, stub)
            }
        if (!started) onFinished()
        return started
    }

    private fun dispatchLatencyRun(
        bound: ITunnelService,
        runId: Long,
        targets: List<LatencyTarget>,
        options: LatencyOptions,
        stub: ILatencyCallback.Stub,
    ): Boolean =
        try {
            // Split into parallel arrays only here, at the wire format, and
            // re-paired by index on the other side.
            val ids = targets.map { it.profileId }.toLongArray()
            val wireModes =
                targets.map { target ->
                    if (target.mode == PingMode.TCP) {
                        LatencyOptionsParcel.MODE_TCP
                    } else {
                        LatencyOptionsParcel.MODE_PROXY_HEAD
                    }
                }.toIntArray()
            bound.startLatencyRun(runId, ids, wireModes, LatencyOptionsParcel.from(options), stub)
            true
        } catch (e: android.os.RemoteException) {
            Log.w(TAG, "latency run failed: ${e.javaClass.simpleName}")
            false
        }

    /**
     * Stops scheduling for [runId].
     *
     * Not instantaneous: a measurement already inside libXray's blocking ping
     * finishes on its own. Callers put their rows back to idle rather than
     * showing a result that never arrived.
     */
    public fun cancelLatencyRun(runId: Long) {
        try {
            service?.cancelLatencyRun(runId)
        } catch (e: android.os.RemoteException) {
            Log.w(TAG, "latency cancel failed: ${e.javaClass.simpleName}")
        }
    }
}
