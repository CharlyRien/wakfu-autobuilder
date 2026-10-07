package me.chosante.bdataextractor

import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.CriterionComparison
import me.chosante.common.ItemEquipCriterion
import me.chosante.common.ItemPlayerStateAtom
import me.chosante.common.ItemStatGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The item-criterion language (`Critere.g`) and the typing of an EQUIP criterion ([classifyEquipCriterion]) on the
 * shapes the 1.93 pool carries — install-independent, so it runs in CI. The decode of the client table itself is
 * covered by the install-gated [ItemCriteriaDecodeTest].
 */
class ItemCriterionParserTest {
    /** The client's breed enum names → breed ids, as the bytecode reads them (SACRIER is the project's SACRIEUR). */
    private val breeds = mapOf("FECA" to 1, "XELOR" to 5, "IOP" to 8, "SACRIER" to 11, "SOUL" to 17)

    private fun classify(raw: String) = classifyEquipCriterion(1, raw, breeds)

    @Test
    fun `grammar - keywords, precedence, hidden parts and arithmetic`() {
        val e = parseCriterionExpression("#not HasEquipmentId(1)# et (GetCharac(\"AP\") + 2 >= 3 ou vrai) && !False")
        assertEquals(
            Crit.And(
                listOf(
                    Crit.Hidden(Crit.Not(Crit.Call("HasEquipmentId", listOf(Crit.Num("1"))))),
                    Crit.Or(
                        listOf(
                            Crit.Cmp(">=", Crit.Arith("+", Crit.Call("GetCharac", listOf(Crit.Str("AP"))), Crit.Num("2")), Crit.Num("3")),
                            Crit.Bool(true)
                        )
                    ),
                    Crit.Not(Crit.Bool(false))
                )
            ),
            e
        )
        // `not` binds looser than a comparison, `-` is unary.
        assertEquals(
            Crit.Not(Crit.Cmp(">", Crit.Call("GetCharac", listOf(Crit.Str("FEROCITY"))), Crit.Neg(Crit.Num("10")))),
            parseCriterionExpression("NOT GetCharac(\"FEROCITY\") > -10")
        )
        assertThrows<CriterionParseException> { parseCriterionExpression("HasEquipmentId(1") }
        assertThrows<CriterionParseException> { parseCriterionExpression("Foo and") }
        assertThrows<CriterionParseException> { parseCriterionExpression("bareIdentifier") }
    }

    @Test
    fun `the nation sword requires its ring`() {
        assertEquals(ItemEquipCriterion(1, "HasEquipmentId(26578)", requiresItems = listOf(26578)), classify("HasEquipmentId(26578)"))
    }

    @Test
    fun `mutually exclusive rings, CRLF line breaks and the unique-ring atom`() {
        val raw = "not HasAnotherSameEquipment()\r\nand not HasEquipmentId(24408)\r\nand not HasEquipmentId(24426)"
        assertEquals(ItemEquipCriterion(1, raw, forbidsItems = listOf(24408, 24426), uniqueEquipped = true), classify(raw))
    }

    @Test
    fun `class items map the client breed to the project class by breed id`() {
        assertEquals(listOf(CharacterClass.SACRIEUR), classify("IsBreed(\"SACRIER\")").classes)
        assertEquals(listOf(CharacterClass.XELOR), classify("IsBreed(\"XELOR\", \"target\")").classes)
        // A breed the client knows but the project has no class for (the unused SOUL breed), and an unknown one, fail.
        assertThrows<IllegalStateException> { classify("IsBreed(\"SOUL\")") }
        assertThrows<IllegalStateException> { classify("IsBreed(\"ROGUE\")") }
        assertThrows<IllegalStateException> { classify("IsBreed(\"IOP\") and IsBreed(\"FECA\")") }
        assertThrows<IllegalStateException> { classify("not IsBreed(\"IOP\")") }
    }

    @Test
    fun `a literal False is never equippable`() {
        assertTrue(classify("False").never)
        assertTrue(classify("not True").never)
        assertEquals(false, classify("not False").never)
    }

    @Test
    fun `stat gates keep the characteristic, the max flag and the comparison, a negation folded in`() {
        assertEquals(
            listOf(ItemStatGate(Characteristic.ACTION_POINT, max = true, comparison = CriterionComparison.LE, value = 11)),
            classify("GetCharacteristicMax(\"AP\", \"target\") <= 11").statGates
        )
        assertEquals(
            listOf(
                ItemStatGate(Characteristic.LOCK, comparison = CriterionComparison.GE, value = 500),
                ItemStatGate(Characteristic.LOCK, comparison = CriterionComparison.LE, value = 600)
            ),
            classify("GetCharac(\"TACKLE\") >= 500 and GetCharac(\"TACKLE\") <= 600").statGates
        )
        assertEquals(
            listOf(ItemStatGate(Characteristic.CRITICAL_HIT, comparison = CriterionComparison.LE, value = -10)),
            classify("not GetCharac(\"FEROCITY\") > -10").statGates
        )
        assertThrows<IllegalStateException> { classify("GetCharac(\"NOT_A_STAT\") > 1") }
        assertThrows<IllegalStateException> { classify("GetCharac(\"AP\") > GetCharac(\"MP\")") }
    }

    @Test
    fun `player-state atoms are kept typed for display`() {
        assertEquals(
            listOf(ItemPlayerStateAtom("GetCompanyRank", comparison = CriterionComparison.GE, value = 4)),
            classify("GetCompanyRank() >= 4").playerState
        )
        assertEquals(listOf(ItemPlayerStateAtom("IsAchievementComplete", args = listOf("1509"))), classify("IsAchievementComplete(1509)").playerState)
        assertEquals(
            listOf(ItemPlayerStateAtom("IsAchievementComplete", args = listOf("1509"), negated = true)),
            classify("not IsAchievementComplete(1509)").playerState
        )
    }

    @Test
    fun `unknown functions and untypable shapes fail loudly`() {
        assertThrows<IllegalStateException> { classify("GetGuildLevel() > 3") }
        assertThrows<IllegalStateException> { classify("IsSomethingNew()") }
        assertThrows<IllegalStateException> { classify("HasEquipmentId(1) or HasEquipmentId(2)") }
        assertThrows<IllegalStateException> { classify("not (HasEquipmentId(1) and HasEquipmentId(2))") }
        assertThrows<IllegalStateException> { classify("HasEquipmentId(1, 2)") }
        assertThrows<IllegalStateException> { classify("HasAnotherSameEquipment()") }
        assertThrows<IllegalStateException> { classify("HasEquipmentId(") }
    }
}
