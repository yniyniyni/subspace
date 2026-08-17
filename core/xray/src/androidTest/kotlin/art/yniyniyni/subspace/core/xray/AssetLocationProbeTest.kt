// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import libXray.LibXray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Proves how — and only how — xray-core can be pointed at the geo directory on
 * Android. This is the mechanism §10.2 calls load-bearing, and the one M5
 * originally got wrong.
 *
 * ## Why both cases are here
 *
 * The negative half is not a curiosity: the
 * shipped design used `android.system.Os.setenv` and every automated test
 * passed, because `GeoAssetPathTest` only ever asserted that `Os.getenv` reads
 * the value back — which exercises libc's `environ`, not Go's. Go's Android
 * shared-library entry point (`runtime/rt0_android_arm64.s`) starts the runtime
 * with a synthetic argv and an **empty envv**, so a gomobile-built library has
 * no environment at all and a C `setenv` can never reach it. If someone
 * "simplifies" the env object away and goes back to `Os.setenv`, this test is
 * what fails.
 *
 * The positive pins the `env` object on the invoke request, applied by
 * `applyEnv` → `os.Setenv` inside Go. That code is upstream's own, currently
 * carried as `third_party/libxray-patches/0001-restore-invoke-env.patch`.
 * **When upstream restores it, this test is the check that the stock AAR still
 * honours it** — see that directory's README.
 *
 * ## Why this is one test method and not two
 *
 * `applyEnv` calls `os.Setenv` **inside Go**, which mutates that process's Go
 * environment for its whole lifetime. Once any call has carried an `env`
 * object, every later call in the same process inherits it. Instrumentation
 * runs a whole class in one process, so a separate negative test would pass or
 * fail depending on what ran before it — it fails exactly this way if the
 * positive runs first. Both halves therefore live in one method, in the only
 * order that can prove anything: negative first, while Go's environment is
 * still empty.
 *
 * That stickiness is a property worth knowing beyond this test: the asset
 * location only needs to be sent once per process, and re-sending it is
 * idempotent.
 */
class AssetLocationProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** `GeoSiteList { entry { country_code: "TEST", domain { type: Domain, value: "example.com" } } }`. */
    private fun geoSiteBytes(): ByteArray {
        val domain =
            byteArrayOf(0x08, 0x02) +
                byteArrayOf(0x12, 0x0b) + "example.com".toByteArray()
        val geoSite =
            byteArrayOf(0x0a, 0x04) + "TEST".toByteArray() +
                byteArrayOf(0x12, domain.size.toByte()) + domain
        return byteArrayOf(0x0a, geoSite.size.toByte()) + geoSite
    }

    private fun stagedGeoDir(): File {
        val dir = File(context.cacheDir, "asset-probe-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "geosite.dat").writeBytes(geoSiteBytes())
        return dir
    }

    /** A config whose only routing rule needs `geosite.dat` to resolve. */
    private fun configReferencing(dir: File): File {
        val config =
            """
            {
              "log": { "loglevel": "warning" },
              "inbounds": [
                {
                  "tag": "socks-in",
                  "protocol": "socks",
                  "listen": "127.0.0.1",
                  "port": 10800,
                  "settings": { "udp": true }
                }
              ],
              "outbounds": [
                { "tag": "direct", "protocol": "freedom" },
                { "tag": "block", "protocol": "blackhole" }
              ],
              "routing": {
                "domainStrategy": "IPIfNonMatch",
                "rules": [
                  { "type": "field", "domain": ["geosite:TEST"], "outboundTag": "block" }
                ]
              }
            }
            """.trimIndent()
        return File(dir, "config.json").apply { writeText(config) }
    }

    @Test
    fun onlyTheInvokeEnvObjectPointsTheCoreAtTheGeoDirectory() {
        // ---- negative, first: Go's environment is still empty ----
        val setenvDir = stagedGeoDir()
        Os.setenv("XRAY_LOCATION_ASSET", setenvDir.absolutePath, true)
        // libc's environ has it...
        Os.getenv("XRAY_LOCATION_ASSET") shouldBe setenvDir.absolutePath

        val withoutEnvObject =
            JSONObject()
                .put("apiVersion", 1)
                .put("method", "testXray")
                .put("payload", JSONObject().put("configPath", configReferencing(setenvDir).absolutePath))

        val failure =
            runCatching {
                LibXrayInvoke.parse("testXray", LibXray.invoke(withoutEnvObject.toString()))
            }.exceptionOrNull()

        // ...and Go cannot see it, so the core falls back to the executable's
        // directory, which on Android is /system/bin.
        requireNotNull(failure) { "expected the core to reject a geosite: rule with no asset location" }
        (failure is XrayException) shouldBe true
        failure.message.orEmpty() shouldContain "/system/bin"

        // ---- positive: the same rule, resolved through the env object ----
        val envDir = stagedGeoDir()
        val withEnvObject =
            JSONObject()
                .put("apiVersion", 1)
                .put("method", "testXray")
                .put("env", JSONObject().put("xray.location.asset", envDir.absolutePath))
                .put("payload", JSONObject().put("configPath", configReferencing(envDir).absolutePath))

        // Throws XrayException if the core rejected it; a clean return means the
        // geosite: reference resolved out of envDir.
        LibXrayInvoke.parse("testXray", LibXray.invoke(withEnvObject.toString()))
    }
}
