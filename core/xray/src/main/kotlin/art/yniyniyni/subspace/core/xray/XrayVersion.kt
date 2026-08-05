// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val METHOD_XRAY_VERSION = "xrayVersion"
private const val FIELD_VERSION = "version"

/**
 * The vendored libXray release's own version string, straight from the Go
 * runtime — not this app's `versionName`, and not a value this module
 * invents. Task 22's About section is the only caller so far; it is public
 * (rather than folded into [XrayController]) because `:feature:settings`
 * needs it and [XrayController] is scoped to one tunnel session (its own
 * KDoc: "create one per connection"), which a version lookup has no reason
 * to be tied to.
 *
 * [LibXrayInvoke] stays `internal` to this module — this is the one
 * permitted seam across the `:core:xray` boundary a feature module may call
 * through, same as [XrayController]'s own public methods.
 *
 * §5.3: every libXray call is slow, hence [io] (default [Dispatchers.IO]).
 * §10.4: a failure here must be legible, not silently swallowed —
 * [art.yniyniyni.subspace.core.xray.LibXrayInvoke.call] already throws
 * [XrayException] on a failed envelope, and this does not catch it. Callers
 * (a `ViewModel`, on a background screen) must treat that as a real failure
 * path — e.g. an "unavailable" state — rather than assuming this always
 * returns.
 */
public suspend fun xrayCoreVersion(io: CoroutineDispatcher = Dispatchers.IO): String =
    withContext(io) {
        val data =
            LibXrayInvoke.call(METHOD_XRAY_VERSION)
                ?: throw XrayException("libXray $METHOD_XRAY_VERSION returned no data")
        data.getString(FIELD_VERSION)
    }
