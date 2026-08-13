// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.Test
import java.io.File

class GeoAssetPathTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theDirectorySitsInInternalStorage() {
        geoAssetDirectory(context) shouldBe File(context.filesDir, "geo")
    }

    /**
     * Read through [Os.getenv], **not** `System.getenv`.
     *
     * `System.getenv` returns a map the JVM snapshots at start-up, so it cannot
     * see a later `Os.setenv` and would make this test fail for a reason that has
     * nothing to do with the code under test. `Os.getenv` reads the live C
     * environment — which is also the layer Go's runtime copies from.
     */
    @Test
    fun theApplicationHasAlreadyInstalledTheVariable() {
        Os.getenv("XRAY_LOCATION_ASSET") shouldBe geoAssetDirectory(context).absolutePath
    }

    @Test
    fun theVariableNamesAnExistingDirectory() {
        File(Os.getenv("XRAY_LOCATION_ASSET")).isDirectory shouldBe true
    }

    @Test
    fun theDirectoryIsNotTheCacheDirectory() {
        // §5.6's neighbour: cache is more readily harvested, and the same
        // reasoning that puts the config in filesDir puts geo data there.
        Os.getenv("XRAY_LOCATION_ASSET") shouldEndWith "/files/geo"
    }

    /**
     * Pins the ordering [installGeoAssetPath]'s KDoc spends four paragraphs
     * defending, not just its presence.
     *
     * The four tests above run as instrumented test methods, which the
     * platform only starts once `Application.onCreate` has already returned —
     * they would stay green even if the call moved from `attachBaseContext`
     * into `onCreate`, catching only outright deletion or a move to
     * `TunnelService`. [GeoEnvRecordingProvider] observes the variable from
     * its own `onCreate`, which the platform runs strictly between
     * `Application.attachBaseContext` and `Application.onCreate` — the exact
     * window that matters. See its KDoc for why that ordering can be trusted.
     */
    @Test
    fun theVariableIsAlreadySetWhenContentProvidersInstall() {
        GeoEnvRecordingProvider.recordedXrayLocationAsset shouldBe geoAssetDirectory(context).absolutePath
    }
}
