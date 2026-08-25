// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

import android.content.Context
import art.yniyniyni.subspace.core.data.GeoAssetRepository
import art.yniyniyni.subspace.core.xray.XrayController
import art.yniyniyni.subspace.core.xray.XrayException
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
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
     * The real `testXray` adapter: writes the composed config to a temp file
     * under `context.cacheDir`, calls [XrayController.validate], and converts a thrown
     * [XrayException] to `false` — [BoundPassthroughValidator] never sees the exception, whose
     * message can quote the config back (§5.6).
     *
     * Cache, not internal storage, unlike [TunnelService]'s own `writeConfig` (§5.6's reasoning
     * there: a *running* tunnel's config sits on disk for the whole session). This file is
     * deleted in the `finally` block below before `validate` even returns to its caller, so the
     * exposure window is one native call, not a session.
     *
     * [geoAssetRepository] supplies a real, existing asset directory for [XrayController]'s
     * constructor. [BoundPassthroughValidator.validate]'s own composed config carries a
     * placeholder asset path instead (see its KDoc) — the core does not read geo files during
     * validation, only when a routing rule matches at runtime, so neither directory's exact
     * contents matter here, only that both resolve to somewhere that exists.
     */
    @Provides
    @Singleton
    fun boundPassthroughValidator(
        @ApplicationContext context: Context,
        geoAssetRepository: GeoAssetRepository,
    ): BoundPassthroughValidator =
        BoundPassthroughValidator { json ->
            val controller = XrayController(geoAssetDir = geoAssetRepository.geoDirectory())
            val file = File.createTempFile("passthrough-validate", ".json", context.cacheDir)
            try {
                file.writeText(json)
                controller.validate(file)
                true
            } catch (_: XrayException) {
                false
            } finally {
                file.delete()
            }
        }
}
