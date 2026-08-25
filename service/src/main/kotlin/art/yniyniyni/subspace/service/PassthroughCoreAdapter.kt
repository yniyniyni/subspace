// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.xray.XrayException
import java.io.File

/**
 * The real `testXray` adapter: writes [json] to a temp file under [cacheDir], calls [validate]
 * on it, and converts a thrown [XrayException] to `false`.
 *
 * Hoisted out of [ServiceModule.boundPassthroughValidator]'s `@Provides` lambda (review
 * Important 5) so it can be exercised on the JVM without Android or a mocking library — the
 * whole point of this function is that it is the one piece of Task 8's wiring that touches
 * files and swallows a config-quoting exception, and an inline lambda inside an `internal
 * object`'s `@Provides` function cannot be called from a test at all.
 *
 * Cache, not internal storage, unlike [TunnelService]'s own `writeConfig` (§5.6's reasoning
 * there: a *running* tunnel's config sits on disk for the whole session). This file is deleted
 * in the `finally` block below before this function returns on every path — success, refusal, or
 * any other throwable — so the exposure window is one native call, not a session.
 *
 * @param validate the core's own check — `XrayController.validate`'s signature exactly, so the
 *   caller passes `controller::validate` and this function stays ignorant of `XrayController`
 *   and the asset directory it carries.
 * @return `true` when the core accepts [json]. Never returns `false` for anything other than a
 *   real [XrayException] — any other throwable (a full cache failing the temp-file write, a
 *   cancellation) propagates rather than being reported as a verdict; [BoundPassthroughValidator]
 *   is where that distinction is turned into "undetermined, leave the verdict alone" (review
 *   Important 2/4).
 */
@Suppress("SwallowedException")
internal suspend fun validateOnCore(
    cacheDir: File,
    validate: suspend (File) -> Unit,
    json: String,
): Boolean {
    val file = File.createTempFile("passthrough-validate", ".json", cacheDir)
    return try {
        file.writeText(json)
        validate(file)
        true
    } catch (e: XrayException) {
        // Deliberately swallowed, not logged: this is the real core refusal, whose message can
        // quote the config back (§5.6, XrayException's own KDoc).
        false
    } finally {
        file.delete()
    }
}
