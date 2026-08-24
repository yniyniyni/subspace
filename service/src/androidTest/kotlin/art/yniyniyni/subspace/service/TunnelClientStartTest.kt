// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TunnelClientStartTest {
    @Test
    fun connectBeforeBindingDeliversTheExactProfileOnceThroughTheForegroundIntent() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = RecordingContext(base)
        val profile = profile()

        TunnelClient(context).connect(profile, rowId = 42L)

        assertEquals(1, context.started.size)
        val intent = context.started.single()
        assertEquals(ComponentName(context, TunnelService::class.java), intent.component)
        assertEquals("art.yniyniyni.subspace.service.action.CONNECT", intent.action)
        val wirePayload =
            intent.getParcelableExtra(
                "art.yniyniyni.subspace.service.extra.PROFILE",
                ProfileParcel::class.java,
            )
        val delivered = connectProfileFrom(intent)
        assertNotNull(wirePayload)
        assertEquals(42L, wirePayload?.rowId)
        assertEquals(profile, wirePayload?.toProfile())
        assertNotNull(delivered)
        assertEquals(42L, delivered?.rowId)
        assertEquals(profile, delivered?.toProfile())
        assertNull("connect must not require a completed bind", context.boundIntent)
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        var boundIntent: Intent? = null

        override fun startForegroundService(service: Intent): ComponentName? {
            started += Intent(service)
            return service.component
        }

        override fun bindService(
            service: Intent,
            connection: android.content.ServiceConnection,
            flags: Int,
        ): Boolean {
            boundIntent = service
            return false
        }
    }

    private fun profile(): Profile =
        Profile(
            id = "exact-profile",
            name = "Exact profile",
            outbound = VlessOutbound(
                address = "203.0.113.7",
                port = 443,
                uuid = "00000000-0000-0000-0000-000000000007",
                flow = "xtls-rprx-vision",
                stream = StreamSettings(
                    network = "tcp",
                    security = Security.Reality(
                        serverName = "example.test",
                        publicKey = "public-key",
                        shortId = "abcd",
                        fingerprint = "chrome",
                        spiderX = "/",
                    ),
                ),
            ),
        )
}
