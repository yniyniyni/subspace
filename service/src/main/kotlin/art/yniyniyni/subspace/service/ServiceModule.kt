// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

import android.content.Context
import art.yniyniyni.subspace.core.data.GeoAssetRepository
import art.yniyniyni.subspace.core.xray.XrayController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for this module's own seams — currently just [PassthroughValidator].
 * [TunnelClient] and friends are bound by construction (`@Inject constructor`) and need no
 * entry here; this exists for the one seam that cannot be, per [BoundPassthroughValidator]'s
 * own KDoc on why its `testConfig` lambda is not a constructor-injected dependency.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ServiceModule {
    @Provides
    @Singleton
    fun passthroughValidator(impl: BoundPassthroughValidator): PassthroughValidator = impl

    /**
     * Wires the real core into [BoundPassthroughValidator]'s `testConfig` lambda. The actual
     * file lifecycle — write, call, delete, convert a real refusal to `false` — lives in
     * [validateOnCore], which is a plain top-level `suspend fun` precisely so it can be unit
     * tested on the JVM (review Important 5); this function's only job is supplying it the
     * real collaborators.
     *
     * **Skips validation entirely when geo assets are not installed yet**, per review Important
     * 3: [XrayController]'s own KDoc and `docs/agent/research/2026-08-11-geo-assets-and-xray-routing.md`
     * §2b both confirm `testXray` *does* resolve geo files for a `geosite:`/`geoip:` rule — it is
     * not a runtime-only concern — so a config carrying routing rules, imported before the geo
     * assets it needs are downloaded, would otherwise get refused by the core for a reason that
     * has nothing to do with the config and resolves itself the moment the assets land. Recording
     * that as [art.yniyniyni.subspace.core.parser.PassthroughRejection.CoreRejected] would be
     * exactly the durable, wrong verdict §10.4 forbids — routing-heavy configs are precisely
     * what M7 targets, so this is not a rare edge case. [BoundPassthroughValidator.validate]
     * returning `true` here (via [GeoAssetRepository.installedFileNames] being empty) means "not
     * determined" the same way an unexpected throwable does — the caller leaves the verdict null
     * and the connect-time backstop (`FailureReason.PassthroughRejectedAtConnect`, Task 9) covers
     * it if the core would in fact refuse it once assets are present. This is a coarse, whole-directory
     * check, not a per-rule one: it does not inspect which specific `.dat` files [json]'s own
     * rules reference, only whether *any* geo asset is installed at all.
     *
     * [geoAssetRepository] supplies the geo directory twice over, and both matter
     * for different reasons. It is what [BoundPassthroughValidator] composes into
     * the config's own `env["xray.location.asset"]`, which is the channel
     * `testXray` actually resolves geo files from (measured 2026-08-31; see that
     * parameter's KDoc and finding F8 in
     * `docs/agent/research/2026-08-25-m7-device-verification.md`). It is also
     * still handed to [XrayController] as `geoAssetDir`, unchanged — that
     * envelope is what a *running* tunnel uses, and leaving the two consistent
     * costs nothing. An earlier version of this KDoc claimed the envelope
     * overrode the composed `env`; it does not, and that claim refused every
     * config the target panel emits.
     */
    @Provides
    @Singleton
    fun boundPassthroughValidator(
        @ApplicationContext context: Context,
        geoAssetRepository: GeoAssetRepository,
    ): BoundPassthroughValidator =
        BoundPassthroughValidator(assetDir = { geoAssetRepository.geoDirectory().absolutePath }) { json ->
            if (geoAssetRepository.installedFileNames().isEmpty()) {
                true
            } else {
                val controller = XrayController(geoAssetDir = geoAssetRepository.geoDirectory())
                validateOnCore(context.cacheDir, controller::validate, json)
            }
        }
}
