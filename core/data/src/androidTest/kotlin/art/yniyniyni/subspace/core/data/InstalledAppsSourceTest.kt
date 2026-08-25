// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data

import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InstalledAppsSourceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = InstalledAppsSource(context)

    // Proves QUERY_ALL_PACKAGES is actually declared and merged: without it this
    // returns a short, filtered list on API 30+ rather than throwing, so an
    // assertion on emptiness would not catch a missing declaration. Android's own
    // packages are the floor that is present on every device.
    @Test
    fun enumerationSeesTheSystemPackages() =
        runTest {
            val names = source.packageNames()

            names.shouldNotBeEmpty()
            names.contains("android") shouldBe true
        }

    @Test
    fun everyAppCarriesANonBlankLabel() =
        runTest {
            source.installed().forEach { app ->
                app.label.isNotBlank() shouldBe true
                app.packageName.isNotBlank() shouldBe true
            }
        }

    // §8: the app must never be routed through itself, and in allow-list mode
    // nothing enforces that but this filter.
    @Test
    fun ourOwnPackageIsNeverOffered() =
        runTest {
            source.packageNames().contains(context.packageName) shouldBe false
            source.installed().find { it.packageName == context.packageName } shouldBe null
        }

    @Test
    fun theListIsSortedByLabelSoThePickerNeedNotSortAgain() =
        runTest {
            val labels = source.installed().map { it.label.lowercase() }

            labels shouldBe labels.sorted()
        }

    @Test
    fun installedAndPackageNamesAgree() =
        runTest {
            source.installed().map { it.packageName }.toSet() shouldBe source.packageNames()
        }
}
