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
 * a local flag about whether a tunnel is up. When nothing is bound — a background
 * worker, a killed UI process — the state is `Disconnected` and this returns
 * null, so the fetch goes direct.
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
    override fun httpProxyPortOrNull(): Int? = client.state.value.httpProxyPortOrNull()
}

/**
 * The whole decision, separated from the binding so it can be tested.
 *
 * `TunnelClient` needs a `Context` and a bound service to construct, which would
 * make the rule above reachable only from an instrumented test. The rule is the
 * part worth pinning — every non-`Connected` state and a zero port must both
 * yield null — so it lives here, where a plain JVM test can reach it.
 */
internal fun ConnectionState.httpProxyPortOrNull(): Int? =
    (this as? ConnectionState.Connected)
        ?.httpProxyPort
        ?.takeIf { it > 0 }
