// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.tunnel

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.TunnelProxyLocator
import art.yniyniyni.subspace.service.TunnelClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the loopback HTTP proxy port out of the service's published state.
 *
 * §5.5: the service owns connection state and this reads it, rather than keeping
 * a local flag about whether a tunnel is up — except for the one flag it does
 * keep, [TunnelClient.isBound], which is not a guess about the tunnel: it is an
 * honest record that the Binder handshake completed and this client currently
 * has a live link to the process that would tell it the truth.
 * **`TunnelClient.state` alone is not enough to
 * answer this.** It is a plain field, refreshed only while bound; once
 * `TunnelClient.unbind()` runs — every time the UI backgrounds — nothing updates
 * it again, so it can go on reporting a `Connected(port)` from a session that
 * ended (the tunnel torn down by `onRevoke()`, or `:bg` killed) while nobody was
 * listening. A background worker reading that frozen value would dial a real
 * port with no tunnel behind it — worst case, a *different* local app that has
 * since bound the same loopback port, since Android gives loopback no per-app
 * isolation, in which case the subscription URL such a worker forms (§5.6, a
 * secret) would be sent there. [isBound] is what lets this decline to answer
 * instead of guessing: unbound means no live link to the source of truth, so no
 * port is handed out, full stop — regardless of what [TunnelClient.state] still
 * says. A bind request which Android accepted but has not connected yet is also
 * unbound for this purpose, so the old cache cannot escape during rebind.
 *
 * `TunnelClient.state` is a `StateFlow`, so `.value` is a plain synchronous read
 * of the last published value. That is what lets [httpProxyPortOrNull] stay a
 * non-suspend function: §5.3 forbids blocking a caller's thread, and collecting
 * the flow to answer a question the cache already holds would do exactly that.
 *
 * A port of 0 means the session carries no HTTP inbound, which is not the same as
 * "connected with a proxy on port 0" — it is normalised to null here so no caller
 * can dial it.
 *
 * This lives in `:app` because it is the only module that can see both
 * `TunnelClient` (`:service`) and the `:core:model` interface `:core:data`
 * consumes. §4 forbids `:core:data` from depending on `:service`, and that
 * constraint is the reason the interface exists at all.
 */
@Singleton
internal class TunnelProxyBinding
@Inject
constructor(
    private val client: TunnelClient,
) : TunnelProxyLocator {
    override fun httpProxyPortOrNull(): Int? = resolveHttpProxyPort(client.isBound, client.state.value)
}

/**
 * The whole decision, separated from the binding so it can be tested.
 *
 * `TunnelClient` needs a `Context` and a bound service to construct, which would
 * make the rule above reachable only from an instrumented test. The rule is the
 * part worth pinning — an unbound client yields null regardless of [state], every
 * non-`Connected` state yields null, and a zero port yields null — so it lives
 * here, where a plain JVM test can reach it.
 */
internal fun resolveHttpProxyPort(
    bound: Boolean,
    state: ConnectionState,
): Int? = if (!bound) null else state.httpProxyPortOrNull()

/** The state-only half of [resolveHttpProxyPort]'s rule — see its KDoc for why [bound] gates it. */
internal fun ConnectionState.httpProxyPortOrNull(): Int? =
    (this as? ConnectionState.Connected)
        ?.httpProxyPort
        ?.takeIf { it > 0 }
