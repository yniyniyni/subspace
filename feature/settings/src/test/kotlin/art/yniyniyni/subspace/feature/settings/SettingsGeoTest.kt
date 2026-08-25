// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.settings

import art.yniyniyni.subspace.core.data.GeoInstallResult
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

    // ── Branch review, Finding 1: geoRowsFor's row assembly ─────────────────────────────────

    /**
     * A branch review found the previous version of this assembly always built a filename's row
     * from whichever catalogue source the picker happened to name for it, so a custom source
     * installed under `geoip.dat` — the add-custom-source form's own filename hint — was rendered
     * as the *v2fly* row, and "Update now" on it silently re-downloaded v2fly over the user's data.
     */
    @Test
    fun `a custom source keeps its own identity against a colliding catalogue filename`() {
        val custom =
            InstalledGeoAsset(
                fileName = "geoip.dat",
                sourceUrl = "https://example.invalid/my-geoip.dat",
                geoType = GeoDataKind.IP,
                sizeBytes = 5_000_000,
                installedAt = 1_754_000_000_000L,
                lastAttemptedAt = 1_754_000_000_000L,
                lastFailure = null,
            )

        val rows =
            geoRowsFor(sources = GeoSourceCatalogue.sources, selectedIds = emptySet(), installed = listOf(custom))

        val geoipRow = rows.single { it.installFileName == "geoip.dat" }
        geoipRow.isCustom shouldBe true
        geoipRow.downloadUrl shouldBe "https://example.invalid/my-geoip.dat"
        geoipRow.installedBytes shouldBe 5_000_000
        // Not "the custom source replaced v2fly" — the untouched default domain slot still shows
        // its own v2fly preview alongside the custom ip row.
        rows.map { it.installFileName } shouldBe listOf("geoip.dat", "geosite.dat")
        rows.single { it.installFileName == "geosite.dat" }.sourceId shouldBe "v2fly-geosite"
    }

    /**
     * §A.5: the size shown before a download must be the cost the user is about to incur, not
     * whatever happens to already be on disk under the same filename from a different source.
     *
     * Branch review, Finding 2: an armed pending selection must NOT also erase that the filename
     * is genuinely still installed — [installedAt] here must keep reading the real install date,
     * not `null`, or this row disagrees with the Routing screen (which reads the installed asset
     * directly) about whether `geosite.dat` is installed, while nothing has actually changed on
     * disk yet.
     */
    @Test
    fun `picking a new source for an installed filename previews its estimate, but keeps the installed date`() {
        val installedFromV2fly =
            InstalledGeoAsset(
                fileName = "geosite.dat",
                sourceUrl = GeoSourceCatalogue.source("v2fly-geosite")!!.downloadUrl,
                geoType = GeoDataKind.DOMAIN,
                sizeBytes = 2_300_000,
                installedAt = 1_754_000_000_000L,
                lastAttemptedAt = 1_754_000_000_000L,
                lastFailure = null,
            )

        val rows =
            geoRowsFor(
                sources = GeoSourceCatalogue.sources,
                selectedIds = setOf("runetfreedom-geosite"),
                installed = listOf(installedFromV2fly),
            )

        val row = rows.single { it.installFileName == "geosite.dat" }
        row.sourceId shouldBe "runetfreedom-geosite"
        row.installedBytes shouldBe null
        row.approximateBytes shouldBe 73_703_302
        row.installedAt shouldBe 1_754_000_000_000L
    }

    /**
     * Branch review, Finding 2's other half: a persisted failure on the file that is actually
     * installed must stay visible while a different source is merely armed, not selected. Before
     * this fix `hasError` was forced to `false` here unconditionally — the same masking class
     * Task 17's own Finding 3 closed, reached through a different door.
     */
    @Test
    fun `an armed pending selection does not hide a persisted failure on the installed asset`() {
        val installedWithFailure =
            InstalledGeoAsset(
                fileName = "geosite.dat",
                sourceUrl = GeoSourceCatalogue.source("v2fly-geosite")!!.downloadUrl,
                geoType = GeoDataKind.DOMAIN,
                sizeBytes = 2_300_000,
                installedAt = null,
                lastAttemptedAt = 1_754_000_000_000L,
                lastFailure = "DownloadFailed",
            )

        val rows =
            geoRowsFor(
                sources = GeoSourceCatalogue.sources,
                selectedIds = setOf("runetfreedom-geosite"),
                installed = listOf(installedWithFailure),
            )

        val row = rows.single { it.installFileName == "geosite.dat" }
        row.hasError shouldBe true
        row.lastFailure shouldBe "DownloadFailed"
    }

    @Test
    fun `an installed asset matching the current selection shows the real installed size and date`() {
        val installed =
            InstalledGeoAsset(
                fileName = "geosite.dat",
                sourceUrl = GeoSourceCatalogue.source("v2fly-geosite")!!.downloadUrl,
                geoType = GeoDataKind.DOMAIN,
                sizeBytes = 2_300_000,
                installedAt = 1_754_000_000_000L,
                lastAttemptedAt = 1_754_000_000_000L,
                lastFailure = null,
            )

        val rows =
            geoRowsFor(
                sources = GeoSourceCatalogue.sources,
                selectedIds = setOf("v2fly-geosite"),
                installed = listOf(installed),
            )

        val row = rows.single { it.installFileName == "geosite.dat" }
        row.sourceId shouldBe "v2fly-geosite"
        row.installedBytes shouldBe 2_300_000
        row.installedAt shouldBe 1_754_000_000_000L
    }

    // ── Branch review minors: no copy-paste can point two outcomes at the same string ───────

    @Test
    fun `each GeoInstallResult maps to a distinct string resource`() {
        val ids = GeoInstallResult.entries.map { it.messageRes() }

        ids.toSet() shouldHaveSize GeoInstallResult.entries.size
    }

    @Test
    fun `every known persisted failure name maps to a distinct string resource`() {
        val knownFailures =
            listOf("InvalidFileName", "DownloadFailed", "InstallFailed", "RecoveryFailed", "Unreadable", "NotGeoData")

        val ids = knownFailures.map { lastFailureMessageRes(it) }

        ids.toSet() shouldHaveSize knownFailures.size
    }
}
