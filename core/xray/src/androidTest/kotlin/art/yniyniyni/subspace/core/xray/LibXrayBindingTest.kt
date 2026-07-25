// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import libXray.LibXray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the libXray AAR loads and its Go runtime initialises on a real device.
 *
 * This is deliberately the cheapest possible call. If the JNI binding or the Go
 * runtime is broken, it fails here — where the cause is obvious — rather than
 * inside the tunnel start sequence, where ARCHITECTURE.md §10.4 warns it would
 * present as an unattributable failure.
 *
 * The API shape asserted here is recorded verbatim in
 * docs/agent/research/libxray-api.md.
 */
class LibXrayBindingTest {

    @Test
    fun coreReportsItsVersionThroughTheInvokeEnvelope() {
        val request = JSONObject()
            .put("apiVersion", 1)
            .put("method", "xrayVersion")
            .toString()

        val response = JSONObject(LibXray.invoke(request))

        assertTrue(
            "expected success, got: ${response.optString("error")}",
            response.getBoolean("success"),
        )
        val version = response.getJSONObject("data").getString("version")
        assertTrue("expected a non-blank version, got '$version'", version.isNotBlank())
    }

    @Test
    fun unknownMethodFailsInTheEnvelopeRatherThanThrowing() {
        // §10.4: failures must be legible. libXray never throws — it encodes the
        // error in the response. Pin that behaviour so XrayController can rely on it.
        val request = JSONObject()
            .put("apiVersion", 1)
            .put("method", "definitelyNotAMethod")
            .toString()

        val response = JSONObject(LibXray.invoke(request))

        assertEquals(false, response.getBoolean("success"))
        assertEquals("unknown method", response.getString("error"))
    }
}
