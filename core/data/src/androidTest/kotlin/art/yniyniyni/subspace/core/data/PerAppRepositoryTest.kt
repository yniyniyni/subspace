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
