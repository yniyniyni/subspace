// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.xray.ComposeResult
import art.yniyniyni.subspace.core.xray.RawConfigComposer
import art.yniyniyni.subspace.core.xray.TunnelSettings
import kotlinx.coroutines.CancellationException

/**
 * Whether xray-core will actually run a stored config.
 *
 * Declared here rather than in `:core:data` or `:feature:profiles` because
 * neither may depend on `:core:xray` (§4), and `:feature:profiles` already
 * depends on this module. The shape is the same one `TunnelProxyLocator`
 * uses — an interface in a module the consumer may reach, implemented where
 * the forbidden dependency is legal — but not the same seam: that interface
 * is declared in `:core:model` and implemented in `:app`
 * ([art.yniyniyni.subspace.tunnel.TunnelProxyBinding]), whereas this one is
 * declared and implemented both in `:service` ([BoundPassthroughValidator]
 * below, wired by [ServiceModule.boundPassthroughValidator]).
 */
public interface PassthroughValidator {
    /**
     * True when the composed form of [rawJson] is accepted by the core, or when the check could
     * not be run at all (e.g. geo assets not installed yet, or a failure unrelated to the core's
     * own verdict) — both leave a row's eligibility undetermined rather than rejected, which is
     * what `true` means to every caller here (see [BoundPassthroughValidator]'s KDoc). Never
     * throws for either of those outcomes; a `CancellationException` from a cancelled coroutine
     * still propagates rather than being reported as any kind of verdict.
     */
    public suspend fun validate(rawJson: String): Boolean
}

/**
 * Validation port for the placeholder inbound.
 *
 * Final review I10: whether `testXray` actually binds this port is **not
 * established** — the previous wording here ("nothing binds it — `testXray`
 * parses and builds the config without listening") was an unsourced claim
 * about xray-core's internal behaviour (§10.5), and it is contradicted by
 * `XrayController.validate`'s own KDoc, which documents `testXray` as
 * building a real core (`StartXray` under the hood, research §4) — not
 * something guaranteed to stop short of listening. A literal is used here
 * anyway: §10.6's rule against hardcoded ports is about a port a *running
 * tunnel* binds and that a real other proxy app could collide with, and
 * this port is used only for one-shot, sequential validation calls, never
 * left listening. But if `testXray` does bind it even briefly, two
 * validations landing on this same literal (or a validation racing a live
 * tunnel — see `docs/agent/research/2026-08-25-m7-device-verification.md`,
 * Question 2) would collide for a reason that has nothing to do with the
 * config being checked — and
 * [BoundPassthroughValidator] cannot tell that collision apart from a real
 * core refusal, so it would be written as a **permanent** `CoreRejected`
 * for a config the core never actually evaluated (§10.4). Tracked as an
 * open question on the §11 device checklist rather than asserted either way:
 * `docs/agent/research/2026-08-25-m7-device-verification.md`, Question 4.
 */
private const val VALIDATION_PORT = 41080

/**
 * Not `@Inject constructor`-ed: Hilt cannot resolve a bare `suspend (String) -> Boolean` from a
 * constructor binding without inventing a qualifier type for it, so [ServiceModule] constructs
 * this directly in a `@Provides` function instead.
 *
 * @param assetDir what the composed config's own `env["xray.location.asset"]`
 *   must name. **This is the only channel `testXray` reads the asset location
 *   from** — measured on a Pixel 8, 2026-08-31, and recorded in
 *   `docs/agent/research/2026-08-25-m7-device-verification.md` finding F8:
 *   with a real directory on `XrayController`'s `geoAssetDir` and a
 *   placeholder here, a `geosite:` rule is refused; with the directories
 *   swapped, it is accepted. An earlier version of this class wrote a
 *   `/data/local/tmp` placeholder here on the opposite claim, which refused
 *   every config the target panel emits. Pinned by
 *   `RawConfigComposerXrayTest.theComposedEnvNotTheInvokeEnvelopeIsWhereTheCoreResolvesGeoFiles`.
 * @param testConfig calls the core's `testXray` on a config's text, and reports whether the
 *   *core itself* accepted it. Taken as a lambda rather than an injected `XrayController`
 *   directly: `:service` carries no mocking library, and a lambda is what makes this class's
 *   composition testable on the JVM ([PassthroughValidatorTest]). The real implementation —
 *   writing the text to a temp file, calling `XrayController.validate`, converting a thrown
 *   `XrayException` to `false` — lives in the Hilt binding ([ServiceModule.boundPassthroughValidator],
 *   via [validateOnCore]) rather than here, per the same "adapter owns the throw-to-Boolean
 *   conversion" reasoning that keeps this class free of `File`/Android APIs. It must never throw
 *   for a real core refusal (that is what `false` means) — only for a genuine failure to run the
 *   check at all, which [validate] below treats as "undetermined", not "rejected".
 */
public class BoundPassthroughValidator(
    private val assetDir: () -> String,
    private val testConfig: suspend (String) -> Boolean,
) : PassthroughValidator {
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun validate(rawJson: String): Boolean {
        val settings =
            TunnelSettings(
                socksPort = VALIDATION_PORT,
                dnsServer = DNS_SERVER_DEFAULT,
                enableSniffing = true,
            )
        val composed = RawConfigComposer.compose(rawJson, settings, assetDir(), override = null)
        if (composed !is ComposeResult.Ok) return false
        return try {
            testConfig(composed.json)
        } catch (e: CancellationException) {
            // Structured concurrency: a cancelled import must not surface as "your config was
            // rejected" (review Important 4). Propagate rather than treat as any kind of verdict.
            throw e
        } catch (e: Exception) {
            // Review Important 2: `testConfig` converts a real core refusal (`XrayException`) to
            // `false` without throwing (see its own KDoc). Anything that reaches here instead —
            // e.g. `createTempFile`/`writeText` failing on a full cache — is a failure to run the
            // check at all, not a verdict from the core. §10.4 forbids a reason that misdescribes
            // the failure: recording CoreRejected for "we could not tell" would be a *permanent*
            // wrong reason, since nothing re-checks a row once it is marked, so this is treated
            // as "not rejected" and the caller leaves the row's verdict untouched.
            true
        }
    }

    private companion object {
        const val DNS_SERVER_DEFAULT = "1.1.1.1"
    }
}
