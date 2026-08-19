// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.model.PerAppMode
import art.yniyniyni.subspace.core.model.PerAppSelection
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class PerAppRepositoryTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var repository: PerAppRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
        repository = PerAppRepository(SettingsRepository(db.settingDao()) { "test-hwid" })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun theDefaultSelectionIsOff() =
        runTest {
            repository.selection.first() shouldBe PerAppSelection.OFF
        }

    // Off means off: a stale selection left over from a mode the user turned off
    // must not reach the Builder.
    @Test
    fun offReportsNoPackagesEvenWhenSomeAreStored() =
        runTest {
            repository.setUserPackages(setOf("com.example.bank"))
            repository.setMode(PerAppMode.Off)

            repository.selection.first() shouldBe PerAppSelection.OFF
        }

    @Test
    fun allowListReportsTheUsersPackages() =
        runTest {
            repository.setMode(PerAppMode.AllowList)
            repository.setUserPackages(setOf("com.example.browser"))

            repository.selection.first() shouldBe
                PerAppSelection(PerAppMode.AllowList, setOf("com.example.browser"))
        }

    @Test
    fun denyListReportsTheUsersPackages() =
        runTest {
            repository.setMode(PerAppMode.DenyList)
            repository.setUserPackages(setOf("com.example.bank", "com.example.maps"))

            repository.selection.first() shouldBe
                PerAppSelection(PerAppMode.DenyList, setOf("com.example.bank", "com.example.maps"))
        }

    // Behaviourally identical to Off — every app is tunnelled — so it collapses,
    // and that case runs M1's proven path rather than a second path beside it.
    @Test
    fun anEmptyDenyListCollapsesToOff() =
        runTest {
            repository.setMode(PerAppMode.DenyList)
            repository.setUserPackages(emptySet())

            repository.selection.first() shouldBe PerAppSelection.OFF
        }

    // The asymmetry is the point: an empty allow-list is NOT off. It is a tunnel
    // no app may use, and it must survive to the service so it can be refused
    // there with a named failure rather than silently becoming "everything works".
    @Test
    fun anEmptyAllowListIsPreservedNotCollapsed() =
        runTest {
            repository.setMode(PerAppMode.AllowList)
            repository.setUserPackages(emptySet())

            repository.selection.first() shouldBe PerAppSelection(PerAppMode.AllowList, emptySet())
        }

    // The picker's flow, and the reason it is a second one. The effective flow
    // above reports OFF here on purpose; a picker seeded from that would show an
    // empty list, and its next save would write that emptiness back — the user
    // loses both the list and the mode, in two saves, silently.
    @Test
    fun theRawSelectionKeepsPackagesParkedBehindOff() =
        runTest {
            repository.setUserPackages(setOf("com.example.bank"))
            repository.setMode(PerAppMode.Off)

            repository.selection.first() shouldBe PerAppSelection.OFF
            repository.userSelection.first() shouldBe
                PerAppSelection(PerAppMode.Off, setOf("com.example.bank"))
        }

    // The other collapsing case, for the same reason: a deny-list the user has
    // emptied is still deny-list *mode* as far as the picker is concerned, even
    // though the tunnel is built as though per-app routing were off.
    @Test
    fun theRawSelectionKeepsTheModeOfAnEmptyDenyList() =
        runTest {
            repository.setMode(PerAppMode.DenyList)
            repository.setUserPackages(emptySet())

            repository.selection.first() shouldBe PerAppSelection.OFF
            repository.userSelection.first() shouldBe PerAppSelection(PerAppMode.DenyList, emptySet())
        }

    // Save a deny-list, switch to Off and save, re-open: the packages are still
    // there. The round trip the picker performs, over the real store.
    @Test
    fun aSavedListSurvivesARoundTripThroughOff() =
        runTest {
            repository.setUserPackages(setOf("com.example.bank", "com.example.maps"))
            repository.setMode(PerAppMode.DenyList)

            // What the picker would write on a save with the mode switched to Off:
            // the same packages it was seeded with, under the new mode.
            val seeded = repository.userSelection.first()
            repository.setUserPackages(seeded.packages)
            repository.setMode(PerAppMode.Off)

            repository.userSelection.first().packages shouldBe
                setOf("com.example.bank", "com.example.maps")
        }

    @Test
    fun theSelectionFlowTracksLaterWrites() =
        runTest {
            repository.setMode(PerAppMode.DenyList)
            repository.setUserPackages(setOf("com.example.bank"))
            repository.selection.first().packages shouldBe setOf("com.example.bank")

            repository.setUserPackages(setOf("com.example.bank", "com.example.maps"))
            repository.selection.first().packages shouldBe setOf("com.example.bank", "com.example.maps")
        }
}
