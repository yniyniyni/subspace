// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.StartupStage
import space.getsub.core.model.failure

/**
 * What `ParcelMappingTest` structurally cannot do.
 *
 * That test's own KDoc says it round-trips "without touching a `Parcel`",
 * because `Parcel` is stubbed to throw in JVM unit tests and this project has no
 * Robolectric. So the field order in `writeToParcel` and in the `Parcel`
 * constructor is checked by nothing on the JVM: swap two same-typed fields and
 * every JVM test still passes while the binder silently transposes them.
 *
 * Deferred out of M5; M8 adds a state to this parcel, so it is fixed here.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionStateParcelRoundTripTest {
    private fun roundTrip(state: ConnectionState): ConnectionState {
        val parcel = Parcel.obtain()
        try {
            ConnectionStateParcel.from(state).writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return ConnectionStateParcel.CREATOR.createFromParcel(parcel).toState()
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun everyStateSurvivesRealMarshalling() {
        listOf(
            ConnectionState.Disconnected,
            ConnectionState.Connecting(StartupStage.EstablishingTun),
            ConnectionState.Connected(sinceEpochMillis = 1_700_000_000_000L, socksPort = 10808, httpProxyPort = 10809),
            ConnectionState.Disconnecting,
            ConnectionState.Reconnecting(FailureReason.CoreStartFailed, attempt = 2),
            failure(FailureReason.ConfigRejected, "a detail"),
        ).forEach { state -> roundTrip(state) shouldBe state }
    }

    /**
     * Two same-typed adjacent fields transposed between write and read is the
     * defect this whole file exists for, and it is invisible to the mapping test.
     */
    @Test
    fun connectedKeepsItsTwoPortsInTheRightOrder() {
        val restored =
            roundTrip(ConnectionState.Connected(sinceEpochMillis = 5L, socksPort = 1080, httpProxyPort = 8080))

        restored shouldBe ConnectionState.Connected(sinceEpochMillis = 5L, socksPort = 1080, httpProxyPort = 8080)
    }
}
