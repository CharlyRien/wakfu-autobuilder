package me.chosante.bdataextractor

import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The Neutralité family's criterion holds EACH secondary mastery ≤ N on its own — an `and`-chain of six 2-argument
 * `GetCharac("<token>", "<who>") <= N` atoms — and the extractor decodes exactly that shape into
 * [SublimationConditionType.SECONDARY_MASTERIES_AT_MOST] (plus Engagement's lone healing atom into
 * [SublimationConditionType.HEALING_MASTERY_AT_MOST]); every other mix fails the extraction instead of silently
 * collapsing into one of them.
 */
class SecondaryMasteryCriterionTest {
    private val tokens = listOf("MELEE_DMG", "RANGED_DMG", "BERSERK_DMG", "CRITICAL_BONUS", "BACKSTAB_BONUS", "HEAL_IN_PERCENT")

    /** The client's own text (State 6931 → StaticEffect 397776): one atom per line. */
    private val neutralite = tokens.joinToString("\nand ") { "GetCharac(\"$it\", \"target\") <= 0" }

    private val secondaryZero = SublimationCondition(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, value = 0)

    @Test
    fun `the six-atom and-chain decodes to the per-stat secondary condition`() {
        assertEquals(secondaryZero, decodeSecondaryMasteryCriterion(neutralite))
        // \r\n line ends and another threshold.
        assertEquals(
            SublimationCondition(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, value = -5),
            decodeSecondaryMasteryCriterion(neutralite.replace("\n", "\r\n").replace("<= 0", "<= -5"))
        )
        // Inflexibilité II's dead branch (330558): the same chain `and False` — the False atom is classified on its own.
        assertEquals(secondaryZero, decodeSecondaryMasteryCriterion("$neutralite\nand False"))
        // Order does not matter, only the set.
        assertEquals(
            secondaryZero,
            decodeSecondaryMasteryCriterion(tokens.reversed().joinToString(" and ") { "GetCharac(\"$it\", \"target\") <= 0" })
        )
    }

    @Test
    fun `Engagement's lone healing atom is healing mastery alone`() {
        assertEquals(
            SublimationCondition(SublimationConditionType.HEALING_MASTERY_AT_MOST, value = 0),
            decodeSecondaryMasteryCriterion("GetCharac(\"HEAL_IN_PERCENT\", \"caster\") <= 0")
        )
    }

    @Test
    fun `criteria without a secondary at-most comparison are left to the atom classifier`() {
        for (other in listOf(
            "GetCharac(\"CRITICAL_BONUS\") <= 0", // Critical Secret: 1-argument crit MASTERY cap
            "GetCharac(\"CRITICAL_BONUS\", \"caster\") >= 2 and GetCharac(\"FEROCITY\", \"caster\") >= 40", // Dénouement
            "GetCharac(\"MELEE_DMG\", \"caster\") > 0", // Chaos
            "(GetCharac(\"DMG_FIRE_PERCENT\", \"caster\") + GetCharac(\"DMG_IN_PERCENT\", \"caster\")) > GetCharac(\"BACKSTAB_BONUS\", \"caster\") " +
                "or (GetCharac(\"DMG_WATER_PERCENT\", \"caster\") + GetCharac(\"DMG_IN_PERCENT\", \"caster\")) > GetCharac(\"BACKSTAB_BONUS\", \"caster\")", // Anatomie
            "GetCharac(\"AP\", \"caster\") <= 10"
        )) {
            assertNull(decodeSecondaryMasteryCriterion(other), other)
        }
    }

    @Test
    fun `any other mix fails loudly instead of collapsing`() {
        val atom = { t: String, n: Int -> "GetCharac(\"$t\", \"target\") <= $n" }
        val bad =
            mapOf(
                "a subset (five of six)" to tokens.drop(1).joinToString(" and ") { atom(it, 0) },
                "a lone non-healing token" to atom("MELEE_DMG", 0),
                "an or-chain" to tokens.joinToString(" or ") { atom(it, 0) },
                "mixed thresholds" to tokens.mapIndexed { i, t -> atom(t, if (i == 0) 10 else 0) }.joinToString(" and "),
                "a token twice" to (tokens.dropLast(1) + "MELEE_DMG").joinToString(" and ") { atom(it, 0) },
                "a negated atom" to tokens.mapIndexed { i, t -> if (i == 2) "not ${atom(t, 0)}" else atom(t, 0) }.joinToString(" and "),
                "another operator beside the chain" to "$neutralite and GetCharac(\"MELEE_DMG\", \"target\") >= -10",
                "mixed arguments" to tokens.mapIndexed { i, t -> "GetCharac(\"$t\", \"${if (i == 0) "caster" else "target"}\") <= 0" }.joinToString(" and "),
                "a 1-argument crit atom in the chain" to "$neutralite and GetCharac(\"CRITICAL_BONUS\") <= 0"
            )
        for ((why, criterion) in bad) {
            val failure = assertThrows(IllegalStateException::class.java, { decodeSecondaryMasteryCriterion(criterion) }, why)
            assertTrue(failure.message.orEmpty().contains("Unsupported secondary-mastery criterion"), "$why: ${failure.message}")
        }
    }

    /**
     * Every criterion of the local client (State 67 → StaticEffect 68) that compares a secondary-mastery token with `<=`
     * decodes without failing, and the subs carrying them are exactly the audited ones: the five six-way Neutralité-family
     * states (Neutralité III, Abandon II, Prétention III, Ambition III, Inflexibilité II — its dead `and False` branch
     * included) and Engagement's lone healing atom. Install-gated (the binaries are not on the CDN), skipped in CI.
     */
    @Test
    fun `the client's secondary-mastery criteria are the audited ones`() {
        val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")
        assumeTrue(File(install, "contents/bdata/${Tables.STATE}.jar").isFile, "no local Wakfu install")
        val effects = loadTable(install, Tables.STATIC_EFFECT, Tables.STATIC_EFFECT_SCHEMA)
        val byId = effects.records.associateBy { it["effect_id"] as Int }

        fun rootState(effectId: Int): Int? {
            var cur = effectId
            val seen = HashSet<Int>()
            while (seen.add(cur)) {
                val e = byId[cur] ?: return null
                val parent = e["parent_id"] as Int
                if ((e["effect_parent_type"] as String).trim() == "STATE") return parent
                cur = parent
            }
            return null
        }
        val secondaryAtMost = Regex("""GetCharac(?:Max)?\("(?:${tokens.joinToString("|")})",\s*"\w+"\)\s*<=""")
        val decoded = HashMap<SublimationConditionType, MutableSet<Int>>()
        val texts = HashMap<Int, MutableSet<String>>()
        for (e in effects.records) {
            val criterion = e["effect_criterion"] as String
            if (!secondaryAtMost.containsMatchIn(criterion)) continue
            val condition = decodeSecondaryMasteryCriterion(criterion)
            assertNotNull(condition, criterion)
            val state = rootState(e["effect_id"] as Int) ?: error("no root state for effect ${e["effect_id"]}")
            decoded.getOrPut(condition!!.type) { sortedSetOf() } += state
            assertEquals(0, condition.value, "threshold of state $state")
            texts.getOrPut(state) { sortedSetOf() } += criterion.replace(Regex("\\s+"), " ").trim()
        }
        texts.toSortedMap().forEach { (state, crits) -> println("SECONDARY_CRITERION state=$state: $crits") }
        assertEquals(setOf(6931, 6932, 6933, 7115, 7256), decoded[SublimationConditionType.SECONDARY_MASTERIES_AT_MOST])
        assertEquals(setOf(7880), decoded[SublimationConditionType.HEALING_MASTERY_AT_MOST])
        val sixWay = tokens.joinToString(" and ") { "GetCharac(\"$it\", \"target\") <= 0" }
        for (state in listOf(6931, 6932, 6933, 7115)) assertEquals(setOf(sixWay), texts[state], "state $state")
        assertEquals(setOf(sixWay, "$sixWay and False"), texts[7256], "Inflexibilité II: live branch + dead `and False` branch")
        assertEquals(setOf("GetCharac(\"HEAL_IN_PERCENT\", \"caster\") <= 0"), texts[7880], "Engagement")
    }
}
