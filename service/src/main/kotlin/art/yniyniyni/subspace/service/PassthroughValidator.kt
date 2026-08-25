// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.xray.ComposeResult
import art.yniyniyni.subspace.core.xray.RawConfigComposer
import art.yniyniyni.subspace.core.xray.TunnelSettings

/**
 * Whether xray-core will actually run a stored config.
 *
 * Declared here rather than in `:core:data` or `:feature:profiles` because
 * neither may depend on `:core:xray` (§4), and `:feature:profiles` already
 * depends on this module — the same seam `TunnelProxyLocator` uses.
 */
public interface PassthroughValidator {
    /** True when the composed form of [rawJson] is accepted by the core. Never throws. */
    public suspend fun validate(rawJson: String): Boolean
}

/**
 * Validation port for the placeholder inbound.
 *
 * Not allocated, because nothing binds it — `testXray` parses and builds the
 * config without listening. A literal is acceptable here and only here; §10.6's
 * rule is about the port a running tunnel *binds*.
 */
private const val VALIDATION_PORT = 41080

/**
 * Not `@Inject constructor`-ed: Hilt cannot resolve a bare `suspend (String) -> Boolean` from a
 * constructor binding without inventing a qualifier type for it, so [ServiceModule] constructs
 * this directly in a `@Provides` function instead.
 *
 * @param testConfig calls the core's `testXray` on a config's text, returning
 *   whether it was accepted. Taken as a lambda rather than an injected
 *   `XrayController` directly: `:service` carries no mocking library, and a
 *   lambda is what makes this class's composition testable on the JVM
 *   ([PassthroughValidatorTest]). The real implementation — writing the text
 *   to a temp file, calling `XrayController.validate`, converting a thrown
 *   `XrayException` to `false` — lives in the Hilt binding
 *   ([ServiceModule.boundPassthroughValidator]) rather than here, per the same
 *   "adapter owns the throw-to-Boolean conversion" reasoning that keeps this
 *   class free of `File`/Android APIs.
 */
public class BoundPassthroughValidator(
    private val testConfig: suspend (String) -> Boolean,
) : PassthroughValidator {
    override suspend fun validate(rawJson: String): Boolean {
        val settings =
            TunnelSettings(
                socksPort = VALIDATION_PORT,
                dnsServer = DNS_SERVER_DEFAULT,
                enableSniffing = true,
            )
        val composed = RawConfigComposer.compose(rawJson, settings, ASSET_DIR_PLACEHOLDER, override = null)
        if (composed !is ComposeResult.Ok) return false
        return runCatching { testConfig(composed.json) }.getOrDefault(false)
    }

    private companion object {
        const val DNS_SERVER_DEFAULT = "1.1.1.1"

        /**
         * The core does not read geo files during validation, only when a rule
         * matches at runtime, so any existing directory serves. The Hilt binding
         * passes the real one; this constant documents why it does not matter.
         */
        const val ASSET_DIR_PLACEHOLDER = "/data/local/tmp"
    }
}
