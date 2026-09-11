// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * What [NetworkTransitionDebouncer] decided about one reported network.
 *
 * A boolean used to be enough, and it is what hid the defect this type exists to
 * close: "do not reconcile now" and "do not reconcile now, but this is not
 * settled yet" were the same answer, so the caller had no way to tell that it
 * owed the transition a second look.
 */
internal sealed interface TransitionDecision {
    /** A genuine change of the network under the tunnel. Report it (§5.2). */
    data object Reconcile : TransitionDecision

    /** Nothing changed. The same network was reported again; no re-check is owed. */
    data object Ignore : TransitionDecision

    /**
     * A *different* network arrived inside the debounce window, so it is being
     * collapsed into the acceptance that opened the window (§5.2) — but the
     * caller must look again in [delayMillis], when the window closes.
     *
     * Deliberately carries no [Network]. What is current when the window closes
     * is a question only `getActiveNetwork()` can answer then; replaying the
     * network that was collapsed would re-pin the tunnel to whatever happened to
     * flap past mid-window.
     */
    data class RecheckAfter(val delayMillis: Long) : TransitionDecision
}

/**
 * Collapses a flapping transition into one reconcile (spec §5.2).
 *
 * Keyed on the network id, because a capability-only change re-delivers the same
 * network and must not restart a working tunnel.
 *
 * Pure and scheduler-free on purpose: it decides, `NetworkMonitor` acts and owns
 * the clock. That is what keeps every rule here directly unit-testable, and the
 * trailing re-check [TransitionDecision.RecheckAfter] asks for does not change
 * it — the decision names a delay, it does not arm a timer.
 */
internal class NetworkTransitionDebouncer(
    private val debounceMillis: Long = 1_000L,
) {
    /**
     * Every method body takes this. The two fields are one piece of state, not
     * two — [decide] reads both and then writes both — and they are touched from
     * more than one thread: `ConnectivityManager` delivers its callbacks on the
     * handler `NetworkMonitor` registers with, and `NetworkMonitor.start()` calls
     * [prime] from whichever coroutine the `tunnelSessionWanted` collector is on.
     * `@Volatile` on each field would not help; it would make each read fresh
     * while still allowing a torn pair.
     *
     * This mattered less when a suppressed callback cost one wasted wakeup. It
     * is load-bearing now that `TunnelService.scheduleBackoffRetry` arms no timer
     * without a network: a lost update here can suppress the only callback that
     * would resume a `Reconnecting` session, which strands it for good.
     */
    private val lock = Any()
    private var lastNetworkId: Long? = null
    private var lastAtMillis: Long = 0L

    fun decide(
        networkId: Long,
        nowMillis: Long,
    ): TransitionDecision =
        synchronized(lock) {
            reconcileDecision(networkId, nowMillis)
        }

    /**
     * [decide]'s verdict, with [lock] already held.
     *
     * Each return is a distinct verdict — the same network reported again, a
     * different network arriving inside the debounce window, and a genuine
     * change — and collapsing them into one expression would lose which rule
     * fired, which is precisely what the caller now needs to know.
     *
     * The middle case returns [TransitionDecision.RecheckAfter] rather than a
     * bare "no", and still does not record [networkId]. Recording it would be the
     * obvious-looking fix and is the wrong one: accepted-state would then name a
     * network that was never reported to the tunnel, so the *next* delivery of
     * that same network would match on id and be ignored — the transition lost in
     * a quieter way. The id stays unrecorded and the caller re-reads
     * `getActiveNetwork()` when the window closes.
     */
    @Suppress("ReturnCount")
    private fun reconcileDecision(
        networkId: Long,
        nowMillis: Long,
    ): TransitionDecision {
        val previousId = lastNetworkId
        if (previousId == networkId) return TransitionDecision.Ignore
        if (previousId != null) {
            val sinceLast = nowMillis - lastAtMillis
            if (sinceLast < debounceMillis) return TransitionDecision.RecheckAfter(debounceMillis - sinceLast)
        }
        lastNetworkId = networkId
        lastAtMillis = nowMillis
        return TransitionDecision.Reconcile
    }

    /**
     * Seeds the last-seen id with [networkId] without counting as a reconcile.
     *
     * `NetworkMonitor.start()` calls this with `ConnectivityManager.activeNetwork`'s
     * id before registering, because `registerNetworkCallback` replays
     * `onAvailable` (and `onCapabilitiesChanged`) immediately for every network
     * already matching the request, including the one that is already current.
     * Left unprimed, that replay has no previous id to compare against, so
     * [decide]'s id-equality guard never applies and the very first callback of
     * every session reads as a genuine transition — tearing down and rebuilding a
     * tunnel that was never actually interrupted.
     *
     * [lastAtMillis] is set far enough in the past that the time-window guard
     * never fires for a network arriving shortly after priming: only the
     * id-equality guard is meant to apply to a primed value, so a genuinely
     * different network right after start() must still reconcile.
     */
    fun prime(networkId: Long) {
        synchronized(lock) {
            lastNetworkId = networkId
            lastAtMillis = PRIMED_AT_MILLIS
        }
    }

    /**
     * Forgets the last-seen network, so the next one reported is treated as a
     * genuine nothing-to-something transition.
     *
     * Called from `NetworkMonitor`'s no-active-network path. Both guards in
     * [decide] exist to avoid restarting a *working* tunnel — the same network
     * reported again, and a flap collapsing into one reconcile. Once the default
     * network is gone there is no working tunnel to protect, and whatever arrives
     * next is the thing the session has been waiting for.
     *
     * This became load-bearing when spec §2.4's no-network-no-timer rule started
     * being enforced on both edges: `scheduleBackoffRetry` now refuses to arm a
     * timer while `activeNetwork` is null, which makes this callback the *only*
     * thing that can resume a `Reconnecting` session with no network. A
     * suppressed `onAvailable` used to cost one wasted wakeup, because the timer
     * would retry anyway; without that backstop it strands the session
     * permanently — with the kill switch on, that is a device left with no
     * connectivity and nothing working to restore it.
     *
     * Two reachable ways that resume is delayed after a loss, both closed by
     * this: a network returning with the `networkHandle` it had before, and a
     * network arriving within [debounceMillis] of the *previous* acceptance (a
     * Wi-Fi to cellular handover during a flap), which [decide] now answers with
     * [TransitionDecision.RecheckAfter]. The second is no longer a lost wake-up —
     * the trailing re-check would find it — but it is a wake-up deferred by up to
     * a full window, and there is no working tunnel here whose restart is worth
     * deferring for.
     */
    fun reset() {
        synchronized(lock) {
            lastNetworkId = null
            lastAtMillis = 0L
        }
    }

    private companion object {
        // Long.MIN_VALUE / 2, not Long.MIN_VALUE itself: reconcileDecision computes
        // `nowMillis - lastAtMillis`, and subtracting from the true minimum would
        // overflow for any nowMillis > 0 (wrapping back around to a small or
        // negative result, which would wrongly re-suppress a real transition).
        private const val PRIMED_AT_MILLIS = Long.MIN_VALUE / 2
    }
}

/**
 * Watches the **physical** networks under the tunnel for as long as a session is
 * **wanted** — not for as long as it is connected (spec §5.1).
 *
 * Fail-closed means sitting with no network and waiting, and this is the thing
 * being waited on. Registering only while connected would make the no-network
 * case unrecoverable without a timer, which spec §2.4 forbids.
 *
 * The constructor parameter is [onNetworkLost], not `onLost`: the overridden
 * `ConnectivityManager.NetworkCallback.onLost(Network)` below has the same name
 * at a different arity, which compiles but reads as recursion to the next
 * person tracing this file.
 */
internal class NetworkMonitor(
    private val context: Context,
    private val onChanged: (Network) -> Unit,
    private val onNetworkLost: () -> Unit,
) {
    private val debouncer = NetworkTransitionDebouncer()

    /**
     * Guards [callback], [callbackThread] and [callbackHandler] against two
     * threads calling [start]/[stop] at once — the `tunnelSessionWanted`
     * collector on its own dispatcher, and `TunnelService.onDestroy()`'s direct
     * [stop] call on whatever thread invokes it. Without this, both could observe
     * a non-null [callback] before either nulls it and both would call
     * `unregisterNetworkCallback` on the same instance. Harmless once — that call
     * is wrapped in [runCatching] — but the [HandlerThread] below is a real
     * resource with a real lifecycle, and double-quitting or leaking one is not a
     * race worth leaving in a file that is otherwise careful about exactly this
     * shape (compare [space.getsub.service.TunnelService]'s own `lock`).
     */
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * The one thread every [settle] runs on.
     *
     * `ConnectivityManager` will dispatch on its own internal handler if none is
     * given, which was fine while callbacks were the only thing calling [settle].
     * The trailing re-check is a second source, and letting it race a live
     * callback would let two [settle] calls interleave between reading
     * `activeNetwork` and asking the debouncer about it — two reads of one fact,
     * applied in either order. Registering with our own handler and posting the
     * re-check to the same one makes [settle] serial by construction rather than
     * by a lock it would otherwise have to grow.
     *
     * `registerNetworkCallback(NetworkRequest, NetworkCallback, Handler)` is API
     * 26, which is `minSdk`, so this needs no version guard.
     */
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    /**
     * The trailing re-check [TransitionDecision.RecheckAfter] asks for.
     *
     * A single stable instance, so [Handler.removeCallbacks] can cancel it and a
     * re-schedule replaces the pending one instead of stacking another beside it.
     *
     * It re-resolves `ConnectivityManager` and re-reads `activeNetwork` rather
     * than closing over either: by the time this runs, the network that was
     * collapsed may be gone, and what matters is what is current *now*. That is
     * the same rule [settle] already follows for the callbacks themselves.
     */
    private val trailingRecheck =
        Runnable {
            context.getSystemService(ConnectivityManager::class.java)?.let(::settle)
        }

    fun start() {
        synchronized(lock) {
            if (callback != null) return
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
            // Priming with whatever is already active before registering is what keeps
            // registerNetworkCallback's immediate replay of that same network from
            // reading as a transition — see NetworkTransitionDebouncer.prime.
            // A null activeNetwork (no connectivity yet) is left unprimed on purpose: the
            // first real onAvailable is then a genuine nothing-to-something transition and
            // must reconcile, whether the session became wanted before or after a network
            // actually exists.
            manager.activeNetwork?.let { active -> debouncer.prime(active.networkHandle) }
            val registered = UnderlyingNetworkCallback(manager)
            // NOT registerDefaultNetworkCallback. That reports the *calling app's*
            // default network, and once this service's own tunnel is up, that IS
            // the VPN — so the platform considers the app's default unchanged when
            // Wi-Fi gives way to cellular underneath it, and delivers nothing.
            // Measured: across a full Wi-Fi -> LTE -> Wi-Fi cycle the declared
            // underlying network stayed pinned to the dead Wi-Fi network, and
            // since this callback is also the only source of
            // ReconcileTrigger.NetworkChanged, restartCoreRetainingTun never ran
            // on a real transition either. Traffic recovered anyway, by the core
            // redialling through the new default on its own — the soft path (§5.4)
            // arriving by accident rather than the hard path this milestone chose.
            //
            // registerBestMatchingNetworkCallback would answer the same question more
            // directly and is API 31, above this module's minSdk of 26. Overriding
            // onCapabilitiesChanged on a plain listen registration is what every
            // supported release does the same way, so there is no guarded second path
            // here and no API-26-to-30 device with weaker transition handling.
            //
            // NET_CAPABILITY_NOT_VPN makes our own tunnel unable to match, which
            // is also why no VPN-filtering is needed downstream any more.
            // This is a "listen" registration, not requestNetwork, so
            // ACCESS_NETWORK_STATE is sufficient and nothing is kept alive by it.
            val request =
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
            val thread = HandlerThread(CALLBACK_THREAD_NAME).apply { start() }
            val handler = Handler(thread.looper)
            // Not swallowed: a registration that fails leaves this monitor dead, and
            // TunnelService's CoroutineExceptionHandler turning that into a legible
            // failure is the existing behaviour. All this adds is not leaking the
            // thread on the way out.
            runCatching { manager.registerNetworkCallback(request, registered, handler) }
                .onFailure { thread.quitSafely() }
                .getOrThrow()
            callback = registered
            callbackThread = thread
            callbackHandler = handler
        }
    }

    /**
     * The registered callback, and the only place the triggers live.
     *
     * A named class rather than an object expression inside [start]: the overrides
     * carry most of the reasoning in this file, and forty lines of it buried in a
     * `val` halfway through registration made [start] read as though registration
     * were the interesting part. Naming it also gives the unit test a type to
     * assert the override *set* against — F3 was one missing override, and nothing
     * in this module could previously see that it was missing.
     *
     * [manager] is the instance [start] resolved, passed in rather than re-fetched
     * on each delivery: it is a process-wide singleton, so a lookup per callback
     * would buy no fresher answer.
     *
     * All three overrides are triggers only: the [Network] they carry is *a*
     * matching network, not the one this session runs over. A "listen"
     * registration reports every network matching the request, so with Wi-Fi and
     * cellular both up, both arrive. `activeNetwork` is the authoritative answer
     * and is re-read in [settle] — verified on device (Pixel 8 / Android 17) to
     * return the physical network even while this service's own VPN is
     * established, because §8 excludes this app's own package from the TUN on
     * every BuilderPlan.
     */
    internal inner class UnderlyingNetworkCallback(
        private val manager: ConnectivityManager,
    ) : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = settle(manager)

        override fun onLost(network: Network) = settle(manager)

        /**
         * The moment the default network actually switches, which neither callback
         * above observes.
         *
         * Android brings Wi-Fi up while cellular is still the default and only moves
         * the default across once Wi-Fi validates. That validation is
         * `NET_CAPABILITY_VALIDATED` appearing on the Wi-Fi network, delivered here —
         * not as a second `onAvailable`. And because mobile data stays up throughout,
         * no `onLost` fires either. So without this override the instant
         * `activeNetwork` changes value is never observed at all: `setUnderlyingNetworks`
         * stays pinned to the old network and §5.4's restart never runs. The device row
         * that passed measured "traffic resumes", which the core redialling on its own
         * also produces — the soft path, not the hard one this milestone chose.
         * https://developer.android.com/develop/connectivity/network-ops/reading-network-state
         *
         * This one is chatty: validation, captive-portal and metered changes, and
         * bandwidth-estimate updates all land here, for every matching network rather
         * than only the default one. It costs a `getActiveNetwork()` binder call each
         * time and nothing else. [settle] re-reads the active network, so chatter about
         * a network that is *not* the default resolves to the id already accepted, and
         * [NetworkTransitionDebouncer.decide] answers [TransitionDecision.Ignore]
         * without writing `lastAtMillis`. Chatter therefore cannot extend the debounce
         * window, and cannot push a real transition out of one.
         */
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = settle(manager)
    }

    /**
     * Resolves what the session is actually running over, and reports it once.
     *
     * Called from all three callbacks, and from [trailingRecheck], because any of
     * them can mean "the network under the tunnel changed": a new physical
     * network arriving, the current one going away, or the default moving across
     * to a network that was already up. Which edge fired does not matter — only
     * what is current afterwards does.
     *
     * A null [ConnectivityManager.getActiveNetwork] is a genuine no-connectivity
     * state and takes §2.4's path: cancel the retry timer and wait, rather than
     * arm a timer that would run in Doze.
     *
     * Runs on [callbackThread], so [onChanged] and [onNetworkLost] are invoked
     * from a background thread — as they were from `ConnectivityManager`'s own
     * dispatch thread before, and never from the main looper.
     */
    private fun settle(manager: ConnectivityManager) {
        val active = manager.activeNetwork
        if (active == null) {
            // Whatever the pending re-check was going to resolve, this answers: there
            // is nothing to resolve to. reset() also discards the state it would have
            // compared against.
            cancelTrailingRecheck()
            debouncer.reset()
            onNetworkLost()
            return
        }
        when (val decision = debouncer.decide(active.networkHandle, SystemClock.elapsedRealtime())) {
            TransitionDecision.Ignore -> Unit
            TransitionDecision.Reconcile -> {
                cancelTrailingRecheck()
                onChanged(active)
            }
            // The transition is collapsed into §5.2's one reconcile, not dropped: this
            // is the re-check that keeps the tunnel from staying pinned to a network
            // that stopped being current mid-window. Re-scheduling on every further
            // in-window delivery is deliberate and cannot run away — the delay is
            // always measured from the acceptance that opened the window, and only a
            // Reconcile moves that, so the target instant is fixed and the delay
            // shrinks monotonically to zero. postDelayed counts uptime while the
            // debouncer measures elapsed realtime, and elapsed never advances slower
            // than uptime, so this can fire late (across a Doze window) but never
            // early into a window that has not actually closed.
            is TransitionDecision.RecheckAfter -> scheduleTrailingRecheck(decision.delayMillis)
        }
    }

    private fun scheduleTrailingRecheck(delayMillis: Long) {
        val handler = synchronized(lock) { callbackHandler } ?: return
        handler.removeCallbacks(trailingRecheck)
        handler.postDelayed(trailingRecheck, delayMillis)
    }

    private fun cancelTrailingRecheck() {
        synchronized(lock) { callbackHandler }?.removeCallbacks(trailingRecheck)
    }

    fun stop() {
        synchronized(lock) {
            val registered = callback ?: return
            callback = null
            val handler = callbackHandler
            val thread = callbackThread
            callbackHandler = null
            callbackThread = null
            // Before unregistering, so nothing is left armed to re-enter settle() on a
            // monitor that is being torn down.
            handler?.removeCallbacks(trailingRecheck)
            val manager = context.getSystemService(ConnectivityManager::class.java)
            // Unregistering a callback that was never registered throws; guarded by
            // the null check above, and swallowed here because a service tearing down
            // must not crash on cleanup (§5.4).
            runCatching { manager?.unregisterNetworkCallback(registered) }
            // quitSafely, not quit: a callback already dispatched runs to completion
            // rather than being abandoned halfway through settle().
            thread?.quitSafely()
        }
    }

    private companion object {
        // 14 characters. Linux truncates thread names to 15, and a name that gets
        // cut off mid-word is the one thing a trace or ANR report will show of this
        // thread.
        private const val CALLBACK_THREAD_NAME = "SubspaceNetMon"
    }
}
