// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace

import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.Test
import java.io.File

/**
 * `geoAssetDirectory` now only names a path — see its KDoc for why the
 * `Os.setenv`/`XRAY_LOCATION_ASSET` mechanism this file used to pin here is
 * gone. `XrayControllerTest.geositeRulesResolveThroughTheEnvObject`
 * (`:core:xray`) is where the actual mechanism — the invoke `env` object — is
 * proven, on the real core.
 */
class GeoAssetPathTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theDirectorySitsInInternalStorage() {
        geoAssetDirectory(context) shouldBe File(context.filesDir, "geo")
    }

    @Test
    fun theDirectoryIsNotTheCacheDirectory() {
        // §5.6's neighbour: cache is more readily harvested, and the same
        // reasoning that puts the config in filesDir puts geo data there.
        geoAssetDirectory(context).absolutePath shouldEndWith "/files/geo"
    }
}
