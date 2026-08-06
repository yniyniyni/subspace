// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.list

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Defect 1 (device-fixes finding): the code tile in [ServerRowItem] took two
 * `Char`s off [ServerRow.name] for its label. A `String` is UTF-16, so an
 * emoji like the rocket in the real fixture names below is a surrogate
 * pair — one `Char` alone from it is an unpaired surrogate, which renders as
 * `<27>`. A flag emoji is two regional-indicator code points, each already a
 * surrogate pair, so even code-point-aware truncation still cuts one in half.
 *
 * Fixture names below are invented, not copied from a real subscription
 * (§5.6) — they reproduce the shapes the device run actually hit: a leading
 * pictograph, a mid-string flag, plain Latin, plain Cyrillic, all-emoji, and
 * blank.
 */
class ServerRowItemTest {
    @Test
    fun `a name starting with an emoji uses the first two lettered words, not the emoji`() {
        "🚀 Авто | Лучший сервер 🇪🇺".codeTileInitials() shouldBe "АЛ"
    }

    @Test
    fun `a flag emoji mid-name is skipped, not split`() {
        "Хельсинки 🇫🇮 XHTTP".codeTileInitials() shouldBe "ХX"
    }

    @Test
    fun `a plain Latin name keeps its previous behaviour`() {
        "Frankfurt".codeTileInitials() shouldBe "F"
        "US East".codeTileInitials() shouldBe "UE"
    }

    @Test
    fun `a plain Cyrillic name keeps its previous behaviour`() {
        "Тестовый Сервер".codeTileInitials() shouldBe "ТС"
    }

    @Test
    fun `a name that is entirely emoji falls back to the placeholder`() {
        "🚀 🇪🇺".codeTileInitials() shouldBe "?"
    }

    @Test
    fun `an empty or whitespace-only name falls back to the placeholder`() {
        "".codeTileInitials() shouldBe "?"
        "   ".codeTileInitials() shouldBe "?"
    }

    @Test
    fun `the same name always yields the same tile`() {
        val name = "🚀 Авто | Лучший сервер 🇪🇺"
        name.codeTileInitials() shouldBe name.codeTileInitials()
    }
}
