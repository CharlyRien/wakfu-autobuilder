package me.chosante.bdataextractor

import kotlinx.serialization.json.Json
import me.chosante.common.I18nText
import me.chosante.common.ItemEquipCriterion
import me.chosante.common.ItemPlayerStateAtom
import me.chosante.common.findRepositoryRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class AchievementNamesDecodeTest {
    @Test
    fun `local achievement table and name namespace reproduce the committed names`() {
        val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")
        assumeTrue(File(install, "lib/wakfu-client.jar").isFile, "no local Wakfu install")
        val resources = File(findRepositoryRoot(), "autobuilder/src/main/resources")
        val criteria = Json.decodeFromString<List<ItemEquipCriterion>>(File(resources, "item-criteria.json").readText())
        val committed = Json.decodeFromString<Map<Int, I18nText>>(File(resources, "achievement-names.json").readText())
        assertEquals(committed, AchievementNames.build(install, criteria))
        assertEquals(62, ClientJar.load(install).achievementNameNamespace())
    }

    @Test
    fun `reference extraction includes negated achievements and rejects malformed ids`() {
        val atom = ItemPlayerStateAtom("IsAchievementComplete", args = listOf("1509"), negated = true)
        val criteria = listOf(ItemEquipCriterion(1, raw = "", playerState = listOf(atom, atom)))
        assertEquals(setOf(1509), AchievementNames.referencedIds(criteria))
        for (args in listOf(emptyList(), listOf("abc"), listOf("-1"), listOf("1509", "2"))) {
            assertThrows(IllegalStateException::class.java) {
                AchievementNames.referencedIds(listOf(criteria.single().copy(playerState = listOf(atom.copy(args = args)))))
            }
        }
    }

    @Test
    fun `a missing localized achievement name fails instead of silently substituting French`() {
        val bundle = I18nBundle(mapOf("fr" to mapOf("content.62.1509" to "Nom"), "en" to emptyMap(), "es" to emptyMap(), "pt" to emptyMap()))
        assertThrows(IllegalStateException::class.java) { bundle.requireText(62, 1509) }
    }
}
