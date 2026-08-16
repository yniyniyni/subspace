// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import art.yniyniyni.subspace.core.data.InstalledGeoAsset
import art.yniyniyni.subspace.core.model.GeoDataKind
import art.yniyniyni.subspace.core.model.GeoSourceCatalogue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Test

class SettingsGeoTest {
    @Test
    fun `an uninstalled catalogue row shows its size and no install date`() {
        val row = geoRowFor(source = GeoSourceCatalogue.source("v2fly-geoip")!!, installed = null)

        row.approximateBytes shouldBe 23_080_744
        row.installedAt shouldBe null
        row.hasError shouldBe false
    }

    @Test
    fun `an installed asset shows its real size rather than the estimate`() {
        val row =
            geoRowFor(
                source = GeoSourceCatalogue.source("v2fly-geoip")!!,
                installed = installedAsset(sizeBytes = 21_000_000, failure = null),
            )

        row.installedBytes shouldBe 21_000_000
    }

    // §A.3.1's persistent error marker: set on failure, cleared on success.
    @Test
    fun `a failed asset carries the error marker`() {
        val row =
            geoRowFor(
                source = GeoSourceCatalogue.source("v2fly-geoip")!!,
                installed = installedAsset(sizeBytes = null, failure = "NotGeoData"),
            )

        row.hasError shouldBe true
    }

    @Test
    fun `the catalogue offers every known source`() {
        GeoSourceCatalogue.sources shouldHaveSize 6
    }

    private fun installedAsset(sizeBytes: Long?, failure: String?) =
        InstalledGeoAsset(
            fileName = "geoip.dat",
            sourceUrl = "https://example.invalid/geoip.dat",
            geoType = GeoDataKind.IP,
            sizeBytes = sizeBytes,
            installedAt = if (failure == null) 1_754_000_000_000L else null,
            lastAttemptedAt = 1_754_000_000_000L,
            lastFailure = failure,
        )
}
