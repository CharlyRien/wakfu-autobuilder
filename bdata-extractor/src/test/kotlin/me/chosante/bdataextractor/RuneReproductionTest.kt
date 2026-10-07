package me.chosante.bdataextractor

import kotlinx.serialization.json.Json
import me.chosante.common.RuneCatalogData
import me.chosante.common.WakfuData
import me.chosante.common.findRepositoryRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

class RuneReproductionTest {
    private val expected = listOf(0, 36, 51, 66, 81, 96, 126, 141, 171, 186, 216)

    @Test
    fun `official rune thresholds and identities reproduce the committed catalog`() {
        val extracted = ItemsCatalog.parseRunes(ItemsCatalog.fetchItemsJson(WakfuData.VERSION), ActionCatalog.fetch(WakfuData.VERSION))
        val committed = Json.decodeFromString<RuneCatalogData>(File(findRepositoryRoot(), "autobuilder/src/main/resources/runes.json").readText())
        assertEquals(expected, extracted.levelRequirements)
        assertEquals(committed, extracted)
    }

    @Test
    fun `different rune threshold lists fail rather than silently choosing one`() {
        val items = """[
          {"definition":{"item":{"id":1,"baseParameters":{"itemTypeId":811},"shardsParameters":{"shardLevelRequirement":[0,36]}}}},
          {"definition":{"item":{"id":2,"baseParameters":{"itemTypeId":811},"shardsParameters":{"shardLevelRequirement":[0,37]}}}}
        ]"""
        assertThrows(IllegalArgumentException::class.java) { ItemsCatalog.parseRunes(items, ActionCatalog.parse("[]")) }
    }
}
