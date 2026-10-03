package me.chosante.ui.components

import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.I18nText
import me.chosante.common.Monster
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.localizedCollator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The boss list behind the picker: boss-tier monsters only, named and sorted in the language of the app. Its first half is a
 * unit test on small fixtures; its second half is a DATA test on the embedded bestiary (`monsters.json`, whose boss tier comes
 * from the hand-curated `monster-overlay.json`), so a game-data bump that adds a garbled entry or an unexplained duplicate
 * fails here instead of reaching the picker.
 */
class BossRosterTest {
    private fun monster(
        id: Int,
        fr: String,
        en: String,
        level: Int = 100,
        rank: Int = 1,
        family: I18nText? = null,
    ) = Monster(
        id = id,
        name = I18nText(fr = fr, en = en, es = en, pt = en),
        level = level,
        hp = 1_000 + id,
        family = family,
        rank = rank,
        fireResistance = 0,
        waterResistance = 0,
        earthResistance = 0,
        airResistance = 0
    )

    // -- the roster ------------------------------------------------------------------------------------------------------

    @Test
    fun `only boss-tier monsters are listed`() {
        val monsters =
            listOf(
                monster(1, "Gobelin", "Goblin", rank = 0),
                monster(2, "Boss", "Boss", rank = 1),
                monster(3, "Golem", "Golem", rank = 3)
            )

        assertThat(bossRoster(monsters, Lang.EN).map { it.id }).containsExactly(2, 3)
    }

    @Test
    fun `the roster is sorted by the name as displayed, so the two languages order the same bosses differently`() {
        val raven = monster(1, fr = "Corbeau", en = "Raven")
        val fox = monster(2, fr = "Renard", en = "Fox")
        val eagle = monster(3, fr = "Aigle", en = "Eagle")

        assertThat(bossRoster(listOf(raven, fox, eagle), Lang.EN)).containsExactly(eagle, fox, raven)
        assertThat(bossRoster(listOf(raven, fox, eagle), Lang.FR)).containsExactly(eagle, raven, fox)
    }

    @Test
    fun `accents and case do not decide the order`() {
        val a = monster(1, fr = "Écureuil", en = "Squirrel")
        val b = monster(2, fr = "Ecume", en = "Foam")
        val c = monster(3, fr = "épinard", en = "Spinach")
        val d = monster(4, fr = "Zèbre", en = "Zebra")

        // Ecume < Écureuil < épinard < Zèbre (a plain code-point sort would push the accented ones after Zèbre).
        assertThat(bossRoster(listOf(d, c, b, a), Lang.FR)).containsExactly(b, a, c, d)
    }

    @Test
    fun `bosses sharing a name stay together, lowest level first`() {
        val high = monster(10, "Cire Momore", "Cire Momore", level = 233)
        val low = monster(11, "Cire Momore", "Cire Momore", level = 58)
        val mid = monster(12, "Cire Momore", "Cire Momore", level = 73)
        val other = monster(13, "Aguabrial", "Aguabrial", level = 200)

        assertThat(bossRoster(listOf(high, other, low, mid), Lang.EN)).containsExactly(other, low, mid, high)
        assertThat(bossRoster(listOf(high, other, low, mid), Lang.FR)).containsExactly(other, low, mid, high)
    }

    @Test
    fun `a boss missing its name in one language shows the other, and sorts by what is shown`() {
        val unnamed = monster(1, fr = "Abribus", en = "")
        val named = monster(2, fr = "Zèbre", en = "Bus Stop")

        assertThat(unnamed.displayName(Lang.EN)).isEqualTo("Abribus")
        assertThat(bossRoster(listOf(named, unnamed), Lang.EN)).containsExactly(unnamed, named) // "Abribus" < "Bus Stop"
    }

    // -- what a row shows -------------------------------------------------------------------------------------------------

    @Test
    fun `the name and the family follow the language of the app`() {
        val boss = monster(1, fr = "Alf Iguem", en = "Rack Istley", family = I18nText(fr = "Moines", en = "Monks", es = "Monjes", pt = "Monges"))

        assertThat(boss.displayName(Lang.EN)).isEqualTo("Rack Istley")
        assertThat(boss.displayName(Lang.FR)).isEqualTo("Alf Iguem")
        assertThat(boss.displayFamily(Lang.EN)).isEqualTo("Monks")
        assertThat(boss.displayFamily(Lang.FR)).isEqualTo("Moines")
    }

    @Test
    fun `a family that only repeats the boss name is not shown`() {
        val self = monster(1, fr = "Excarnus", en = "Excarnus", family = I18nText("Excarnus", "Excarnus", "Excarnus", "Excarnus"))
        val none = monster(2, fr = "Rushu", en = "Rushu", family = null)
        val blank = monster(3, fr = "Tal Kasha", en = "Tal Kasha", family = I18nText("", "", "", ""))

        assertThat(self.displayFamily(Lang.EN)).isNull()
        assertThat(none.displayFamily(Lang.EN)).isNull()
        assertThat(blank.displayFamily(Lang.FR)).isNull()
    }

    @Test
    fun `a typed query matches the name in either language, ignoring case and surrounding spaces`() {
        val boss = monster(1, fr = "Alf Iguem", en = "Rack Istley")

        assertThat(boss.matchesQuery("rack")).isTrue()
        assertThat(boss.matchesQuery("  IGUEM ")).isTrue()
        assertThat(boss.matchesQuery("")).isTrue()
        assertThat(boss.matchesQuery("Momore")).isFalse()
    }

    // -- the embedded bestiary --------------------------------------------------------------------------------------------

    private val bestiary: List<Monster> by lazy { WakfuBestBuildFinderAlgorithm.monsters }

    /** A name a player can read: letters (and digits, e.g. "Korbot K-800"), spaces, and the punctuation real names use. */
    private fun String.isReadableName(): Boolean = any { it.isLetter() } && all { it.isLetterOrDigit() || it in " '’-,.:()" }

    @Test
    fun `no boss name is garbled, in any language`() {
        val roster = bossRoster(bestiary, Lang.EN)
        assertThat(roster).describedAs("the bestiary has bosses").hasSizeGreaterThan(100)

        val garbled =
            roster.flatMap { boss ->
                listOf("fr" to boss.name.fr, "en" to boss.name.en, "es" to boss.name.es, "pt" to boss.name.pt)
                    .filterNot { (_, text) -> text.isReadableName() }
                    .map { (language, text) -> "boss ${boss.id} ($language): '$text'" }
            }

        assertThat(garbled)
            .describedAs(
                "Boss names a player cannot read. A placeholder row from the client's data must not be boss-tier: remove its id from " +
                    "autobuilder/src/main/resources/monster-overlay.json and regenerate monsters.json (./gradlew :bdata-extractor:run)."
            ).isEmpty()
    }

    @Test
    fun `the only bosses sharing a name are distinct monsters, told apart by their level`() {
        // "Cire Momore" exists three times, at levels 58 / 73 / 233, with its own life and resistances each: three monsters of
        // the same dungeon boss, not a duplicated record — kept, and the picker row shows the level.
        val explainedSharedNames = setOf("cire momore")

        for (lang in Lang.entries) {
            val shared =
                bossRoster(bestiary, lang)
                    .groupBy { it.displayName(lang).trim().lowercase() }
                    .filterValues { it.size > 1 }

            assertThat(shared.keys)
                .describedAs(
                    "Bosses sharing a name in $lang. If they are different monsters (different level and life), list the name in " +
                        "explainedSharedNames; if they are one record twice, drop the extra id from monster-overlay.json."
                ).isEqualTo(explainedSharedNames)
            shared.forEach { (name, group) ->
                assertThat(group.map { it.level }).describedAs("'$name': the levels shown in the picker tell the bosses apart").doesNotHaveDuplicates()
                assertThat(group.map { it.hp }).describedAs("'$name': different monsters, not the same record twice").doesNotHaveDuplicates()
            }
        }
        assertThat(bossRoster(bestiary, Lang.EN).filter { it.name.en == "Cire Momore" }.map { it.level }).containsExactly(58, 73, 233)
    }

    @Test
    fun `every boss appears once`() {
        val roster = bossRoster(bestiary, Lang.EN)

        assertThat(roster.map { it.id }).doesNotHaveDuplicates()
        assertThat(roster).hasSize(bestiary.count { it.isBoss })
    }

    @Test
    fun `the embedded roster is sorted by the displayed name in both languages`() {
        for (lang in Lang.entries) {
            val collator = localizedCollator(lang)
            val roster = bossRoster(bestiary, lang)

            roster.zipWithNext().forEach { (before, after) ->
                val order = collator.compare(before.displayName(lang).trim(), after.displayName(lang).trim())
                assertThat(order).describedAs("$lang: '${before.displayName(lang)}' before '${after.displayName(lang)}'").isLessThanOrEqualTo(0)
                if (order == 0) assertThat(before.level).isLessThanOrEqualTo(after.level)
            }
        }
    }

    @Test
    fun `English names are proper-cased, not lowercased, so there is no reason left to show French in an English app`() {
        // The picker used to show the French name regardless of the app language "because the English names are lowercased".
        val lowercased = bossRoster(bestiary, Lang.EN).filter { it.name.en.any { c -> c.isLetter() } && it.name.en == it.name.en.lowercase() }

        assertThat(lowercased.map { it.name.en }).isEmpty()
    }
}
