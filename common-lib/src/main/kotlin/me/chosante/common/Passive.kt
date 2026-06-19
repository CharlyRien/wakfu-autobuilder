package me.chosante.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * A class passive spell, decoded from the baked `spell-passives-v<VERSION>.json` (produced by
 * `bdata-extractor` from the local game client — see `docs/SPELL_PASSIVES_EXTRACTION.md`). The runtime
 * intentionally reads only a **subset** of the file's fields (the rest — raw effect ids, declared-effect
 * trees, the `has*` flags — drive the extractor's conservatism, not the solver), so it is decoded with a
 * lenient JSON that ignores the unmodeled keys.
 *
 * A player equips passives into a limited number of slots (see `PassiveCatalog.slotsForLevel`). A passive's
 * [flatStats] (the permanent, unconditional, flat bonuses) always fold into the solve; its conditional /
 * triggered / scripted effects (damage that lives in combat state the static optimizer can't see) are not
 * modeled — those passives are still carried for *selection and display*. [fullyDeclarative] (a passive with
 * **no** such non-flat effects at all) only gates whether the solver may AUTO-PICK it ([solverChoosable]).
 */
@Serializable
data class Passive(
    val spellId: Int,
    val name: String? = null,
    // Game data stores the class as its readable name ("FECA", …); kept as a String because CharacterClass
    // is not @Serializable. Resolve via [characterClass].
    @SerialName("class") val clazz: String,
    // Spell sprite id — names the icon PNG (`assets/spells/<gfxId>.png`, extracted from the client's gui.jar).
    // Defaulted so a pre-`gfxId` artifact still decodes (lenient JSON skips extra keys, not missing ones).
    val gfxId: Int = 0,
    val description: String? = null,
    // Permanent, unconditional, flat positive stats (keyed by Characteristic name). Decoded as Double to
    // tolerate the extractor's number formatting; folded to Int via [flatStats].
    val flatBuildStats: Map<String, Double> = emptyMap(),
    val appliedStateIds: List<Int> = emptyList(),
    // True ⟺ the passive has no scripted/state/conditional/triggered effects at all. NOTE: [flatStats] is
    // ALWAYS the safe-to-fold subset (the extractor keeps only permanent + unconditional + flat + positive
    // effects, per-effect, even for a passive that is not fully declarative), so folding it is correct
    // regardless of this flag — `fullyDeclarative` only gates solver AUTO-PICK ([solverChoosable]).
    val fullyDeclarative: Boolean = false,
) {
    /** The owning class, or [CharacterClass.UNKNOWN] if the data carries an unrecognized label. */
    val characterClass: CharacterClass
        get() = runCatching { CharacterClass.valueOf(clazz) }.getOrDefault(CharacterClass.UNKNOWN)

    /** The flat stat bonuses this passive grants, resolved to [Characteristic] (unknown keys dropped). */
    val flatStats: Map<Characteristic, Int> by lazy {
        flatBuildStats
            .mapNotNull { (key, value) ->
                runCatching { Characteristic.valueOf(key) }.getOrNull()?.let { it to value.roundToInt() }
            }.toMap()
    }

    /** Whether the solver may auto-pick this passive: fully declarative AND it actually grants flat stats. */
    val solverChoosable: Boolean
        get() = fullyDeclarative && flatStats.isNotEmpty()
}
