// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VmessOutbound
import art.yniyniyni.subspace.service.ProfileParcel
import art.yniyniyni.subspace.service.TunnelClient
import art.yniyniyni.subspace.service.TunnelService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val PROFILE_EXTRA = "art.yniyniyni.subspace.service.extra.PROFILE"
private const val CONNECT_RECEIVED = 1

@RunWith(AndroidJUnit4::class)
class TunnelClientServiceBoundaryTest {
    @Test
    fun connectBeforeBindingReachesActualBackgroundServiceCoordinatorExactlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = profile()
        val receipts = AtomicInteger()
        val delivered = AtomicReference<ProfileParcel>()
        val received = CountDownLatch(1)
        val observer =
            Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    if (message.what != CONNECT_RECEIVED) return@Handler false
                    val resultData = message.data
                    resultData.classLoader = ProfileParcel::class.java.classLoader
                    delivered.set(resultData.getParcelable(PROFILE_EXTRA, ProfileParcel::class.java))
                    receipts.incrementAndGet()
                    received.countDown()
                    true
                },
            )

        try {
            val client = testObservedClient(context, observer)
            assertFalse(client.isBound)

            client.connect(profile, rowId = 42L)

            assertTrue("actual :bg service did not report coordinator receipt", received.await(10, TimeUnit.SECONDS))
            Thread.sleep(250)
            assertEquals(1, receipts.get())
            assertEquals(42L, delivered.get()?.rowId)
            assertEquals(profile, delivered.get()?.toProfile())
            assertFalse("connect must not require a completed bind", client.isBound)
        } finally {
            context.stopService(Intent(context, TunnelService::class.java))
        }
    }

    /** The debug-only constructor stays internal to :service's source API. */
    private fun testObservedClient(
        context: Context,
        observer: Messenger,
    ): TunnelClient {
        val constructor =
            TunnelClient::class.java.getDeclaredConstructor(Context::class.java, Messenger::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(context, observer)
    }

    private fun profile(): Profile =
        Profile(
            id = "exact-profile",
            name = "Exact profile",
            outbound = VmessOutbound(
                address = "203.0.113.7",
                port = 443,
                uuid = "00000000-0000-0000-0000-000000000007",
                alterId = 0,
                security = "auto",
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
