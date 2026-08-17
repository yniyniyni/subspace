// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GeoCategoriesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** The shape `geo/count.go` writes — see research §5. */
    @Test
    fun `reads codes and rule counts`() {
        val file = File(temporaryFolder.root, "geosite.json")
        file.writeText(
            """{"codes":[{"code":"cn","ruleCount":12345},{"code":"ads","ruleCount":42}],
               |"categoryCount":2,"ruleCount":12387}
            """.trimMargin(),
        )

        val categories = GeoCategories.read(file)

        categories shouldHaveSize 2
        categories.first().code shouldBe "cn"
        categories.first().ruleCount shouldBe 12345
    }

    // A missing or corrupt file must degrade the picker to free text, never
    // crash the editor.
    @Test
    fun `an absent file yields no categories`() {
        GeoCategories.read(File(temporaryFolder.root, "nope.json")) shouldBe emptyList()
    }

    @Test
    fun `malformed json yields no categories`() {
        val file = File(temporaryFolder.root, "geosite.json").apply { writeText("not json") }

        GeoCategories.read(file) shouldBe emptyList()
    }
}
