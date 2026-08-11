// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.model.GeoDataKind
import art.yniyniyni.subspace.core.model.GeoValidation
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Instrumented rather than JVM: `countGeoData` is a native call, so this cannot
 * run on the JVM (the same reason `LibXrayInvokeTest` splits envelope parsing out
 * from `LibXrayInvoke.call`).
 */
class LibXrayGeoDataValidatorTest {
    private lateinit var dir: File
    private val validator = LibXrayGeoDataValidator()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.cacheDir, "geo-validator-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @Test
    fun reportsUnreadableWhenTheFileIsAbsent() = runTest {
        validator.validate(dir, "geosite", GeoDataKind.DOMAIN) shouldBe GeoValidation.Unreadable
    }

    // The failure this exists to catch: a captive portal or a GitHub outage page
    // arrives with HTTP 200 and is perfectly valid HTTP. Installing it turns every
    // rule into a startup failure with no obvious cause.
    @Test
    fun rejectsAnHtmlErrorPageThatArrivedWithA200() = runTest {
        File(dir, "geosite.dat").writeText("<!doctype html><html><body>404 Not Found</body></html>")

        validator.validate(dir, "geosite", GeoDataKind.DOMAIN) shouldBe GeoValidation.NotGeoData
    }

    // Empty is Unreadable, NOT NotGeoData, and the distinction is load-bearing:
    // protobuf accepts zero bytes as a valid *empty* message, so countGeoData
    // would parse an empty file successfully and report it installable. The
    // explicit length check in the implementation is what catches it.
    @Test
    fun reportsUnreadableForAnEmptyFile() = runTest {
        File(dir, "geoip.dat").writeBytes(ByteArray(0))

        validator.validate(dir, "geoip", GeoDataKind.IP) shouldBe GeoValidation.Unreadable
    }

    @Test
    fun rejectsRandomBytes() = runTest {
        // Starts with 0x00 — field number 0, which is invalid in protobuf, so
        // Unmarshal errors rather than silently producing an empty message.
        File(dir, "geoip.dat").writeBytes(ByteArray(512) { (it % 251).toByte() })

        validator.validate(dir, "geoip", GeoDataKind.IP) shouldBe GeoValidation.NotGeoData
    }

    /**
     * The positive case, without shipping a 23 MB fixture.
     *
     * A `GeoSiteList` is small enough to hand-encode. From
     * `common/geodata/geodat.proto`: `GeoSiteList { repeated GeoSite entry = 1 }`,
     * `GeoSite { string country_code = 1; repeated Domain domain = 2 }`,
     * `Domain { Type type = 1; string value = 2 }` with `Type.Domain = 2`.
     *
     * This proves the whole path — that `countGeoData` is reachable, that the
     * `geoType` mapping is right, and that a valid file produces the `.json`
     * Task 8 moves and the rule editor reads.
     */
    @Test
    fun acceptsAMinimalGeositeDatabaseAndWritesTheCodeList() = runTest {
        val domain =
            byteArrayOf(0x08, 0x02) + // type = Domain
                byteArrayOf(0x12, 0x0b) + "example.com".toByteArray()
        val geoSite =
            byteArrayOf(0x0a, 0x04) + "TEST".toByteArray() + // country_code
                byteArrayOf(0x12, domain.size.toByte()) + domain
        val list = byteArrayOf(0x0a, geoSite.size.toByte()) + geoSite

        File(dir, "geosite.dat").writeBytes(list)

        validator.validate(dir, "geosite", GeoDataKind.DOMAIN) shouldBe GeoValidation.Valid
        // countGeoData's second output — the rule editor's category list.
        File(dir, "geosite.json").readText().contains("TEST") shouldBe true
    }

    @Test
    fun acceptsAMinimalGeoipDatabaseAndWritesTheCodeList() = runTest {
        File(dir, "geoip.dat").writeBytes(minimalGeoIpList())

        validator.validate(dir, "geoip", GeoDataKind.IP) shouldBe GeoValidation.Valid
        File(dir, "geoip.json").readText().contains("TEST") shouldBe true
    }

    @Test
    fun rejectsAGeoipDatabaseWhenItIsDeclaredAsDomainData() = runTest {
        // The CIDR message uses field 1 as bytes, while GeoSite's corresponding
        // Domain field requires a varint type; parsing this declared-kind mismatch
        // must fail rather than treating an IP database as site data.
        File(dir, "geoip.dat").writeBytes(minimalGeoIpList())

        validator.validate(dir, "geoip", GeoDataKind.DOMAIN) shouldBe GeoValidation.NotGeoData
    }

    private fun minimalGeoIpList(): ByteArray {
        val cidr = byteArrayOf(0x0a, 0x04, 192.toByte(), 0x00, 0x02, 0x01, 0x10, 0x18)
        val geoIp =
            byteArrayOf(0x0a, 0x04) + "TEST".toByteArray() + // country_code
                byteArrayOf(0x12, cidr.size.toByte()) + cidr
        return byteArrayOf(0x0a, geoIp.size.toByte()) + geoIp
    }
}
