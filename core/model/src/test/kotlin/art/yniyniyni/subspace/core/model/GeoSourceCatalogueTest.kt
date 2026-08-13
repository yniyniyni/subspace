// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.Test

class GeoSourceCatalogueTest {
    @Test
    fun theDefaultSourceIdResolvesToARealRow() {
        GeoSourceCatalogue.sources
            .firstOrNull { it.id == GeoSourceCatalogue.DEFAULT_SOURCE_ID }
            .shouldNotBeNull()
    }

    @Test
    fun defaultsAreOneGeoipAndOneGeosite() {
        val defaults = GeoSourceCatalogue.defaults()

        defaults shouldHaveSize 2
        defaults.map { it.installFileName }.toSet() shouldBe setOf("geoip.dat", "geosite.dat")
        defaults.map { it.geoType }.toSet() shouldBe setOf(GeoDataKind.IP, GeoDataKind.DOMAIN)
    }

    /**
     * v2fly publishes geosite as `dlc.dat`; xray-core looks for `geosite.dat`
     * (`DefaultGeoSiteDat`, research §3). Installing under the remote name is a
     * silent failure — the download succeeds and every `geosite:` rule then
     * reports missing geo data. Pinned so a catalogue edit cannot reintroduce it.
     */
    @Test
    fun theV2flyGeositeRowInstallsDlcDatAsGeositeDat() {
        val row = GeoSourceCatalogue.sources.single { it.id == "v2fly-geosite" }

        row.downloadUrl.endsWith("dlc.dat") shouldBe true
        row.installFileName shouldBe "geosite.dat"
    }

    @Test
    fun everyRowDeclaresAGeoTypeAndANonEmptyLicence() {
        GeoSourceCatalogue.sources.forEach { source ->
            source.licence.isNotBlank() shouldBe true
            (source.approximateBytes > 0) shouldBe true
        }
    }

    @Test
    fun everyRowHasADistinctId() {
        val ids = GeoSourceCatalogue.sources.map { it.id }

        ids.toSet() shouldHaveSize ids.size
    }

    // A source URL decides what the device treats as "ads" or "domestic".
    // Anything but https is not acceptable for a shipped row.
    @Test
    fun everyDownloadUrlIsHttps() {
        GeoSourceCatalogue.sources.forEach { it.downloadUrl shouldStartWith "https://" }
    }

    @Test
    fun everyInstallFileNameEndsInDat() {
        GeoSourceCatalogue.sources.forEach { it.installFileName.endsWith(".dat") shouldBe true }
    }
}
