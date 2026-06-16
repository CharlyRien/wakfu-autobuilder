package me.chosante.spellextractor

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.chosante.common.I18nText
import me.chosante.common.Spell
import me.chosante.common.findRepositoryRoot
import java.io.File

/**
 * Wakfu data version the produced dataset is stamped with. Must match `VERSION` in
 * `autobuilder/.../Main.kt` (the resource is loaded as `spells-v$VERSION.json`). Overridable via the
 * first CLI argument.
 */
private const val DEFAULT_VERSION = "1.91.1.54"

/**
 * Builds `autobuilder/src/main/resources/spells-v<VERSION>.json` by crawling the Ankama encyclopedia
 * (`docs/SPELLS_AND_COMBO_RESEARCH.md`). For each of the 18 classes it reads the spell list, then each
 * spell's detail page, parsing element / AP / range / base damage with [SpellScraper]. French names are
 * enriched from the French class pages; es/pt default to the English name.
 *
 * Resumable: pages are cached under `spells-extractor/.cache/`, so a re-run only fetches what is missing.
 *
 * Run with: `./gradlew :spells-extractor:run` (optionally `--args="<version>"`).
 */
suspend fun main(args: Array<String>) {
    val version = args.firstOrNull() ?: DEFAULT_VERSION
    val repoRoot = findRepositoryRoot()
    val client = EncyclopediaClient(cacheDir = File(repoRoot, "spells-extractor/.cache"))

    println("Priming encyclopedia session…")
    client.prime()

    val allSpells = mutableListOf<Spell>()
    val classReports = mutableListOf<ClassReport>()

    for (ref in ClassRef.ALL) {
        val enListing = client.fetch(EncyclopediaClient.BASE + ref.classPath(Locale.EN), cacheKey = "class-en-${ref.encyclopediaId}")
        if (enListing == null) {
            System.err.println("! ${ref.clazz}: class page unreachable; skipping")
            classReports += ClassReport(ref.clazz, 0, 0, 0, listOf("<class page unreachable>"))
            continue
        }
        val stubs = SpellScraper.parseClassListing(enListing)

        // French names: best-effort id -> fr name from the FR class page (falls back to EN per spell).
        val frNames =
            client
                .fetch(EncyclopediaClient.BASE + ref.classPath(Locale.FR), cacheKey = "class-fr-${ref.encyclopediaId}")
                ?.let { SpellScraper.parseClassListing(it).associate { s -> s.id to s.name } }
                ?: emptyMap()

        val classSpells = mutableListOf<Spell>()
        val incomplete = mutableListOf<String>()
        for (stub in stubs) {
            val page = client.fetch(EncyclopediaClient.BASE + stub.detailPath(ref), cacheKey = "spell-${stub.id}")
            if (page == null) {
                incomplete += "${stub.name} (#${stub.id}: page unreachable)"
                classSpells +=
                    Spell(
                        id = stub.id,
                        clazz = ref.clazz,
                        name = nameOf(stub, frNames),
                        iconId = stub.iconId,
                        missingFields = listOf("<page unreachable>")
                    )
                continue
            }
            val detail = SpellScraper.parseSpellPage(page)
            val spell = toSpell(ref, stub, detail, frNames)
            if (spell.missingFields.isNotEmpty()) {
                incomplete += "${spell.name.en} (#${spell.id}: ${spell.missingFields.joinToString(",")})"
            }
            classSpells += spell
        }

        val withDamage = classSpells.count { it.hasDamage }
        classReports += ClassReport(ref.clazz, classSpells.size, withDamage, classSpells.count { it.missingFields.isNotEmpty() }, incomplete)
        allSpells += classSpells
        println("  ${ref.clazz}: ${classSpells.size} spells, $withDamage with damage")
    }

    val sorted = allSpells.sortedWith(compareBy({ it.clazz.name }, { it.id }))
    val outputDir = File(repoRoot, "autobuilder/src/main/resources").apply { mkdirs() }
    val outputFile = File(outputDir, "spells-v$version.json")
    outputFile.writeText(Json { prettyPrint = false }.encodeToString(ListSerializer(Spell.serializer()), sorted))

    printReport(sorted, classReports, outputFile)
}

/**
 * Builds the [Spell], flagging only fields that are genuinely *expected but unreadable*.
 *
 * "Is this a damage spell" can only be told from the detail page (the listing tags every spell the
 * same), so it is derived from the parse: a "damage:" line ([SpellDetail.baseDamage]) or an element
 * picto ([SpellDetail.rawElement]). Only such spells get their missing damage fields flagged — utility
 * and passive spells legitimately have no element/damage and are never flagged for it.
 */
private fun toSpell(
    ref: ClassRef,
    stub: SpellStub,
    detail: SpellDetail,
    frNames: Map<Int, String>,
): Spell {
    val missing = mutableListOf<String>()
    // A damage spell carries a "damage:" number or a standard 4-element picto. A LIGHT/STASIS picto
    // alone is NOT a reliable signal — the encyclopedia reuses the Light picto as a generic marker on
    // heals/passives — so it only counts when a damage number is also present (handled by baseDamage).
    val looksLikeDamage = detail.baseDamage != null || detail.element != null
    if (looksLikeDamage) {
        if (detail.element == null) {
            // Either nothing parsed, or a real element with no 4-element mapping (LIGHT / STASIS) —
            // name it so the gap is specific and honest.
            missing += detail.rawElement?.let { "element($it)" } ?: "element"
        }
        if (detail.baseDamage == null) missing += "baseDamage"
        if (detail.apCost == null) missing += "apCost"
        if (detail.rangeMin == null) missing += "range"
    }
    // A captured resistance debuff whose target picto we couldn't confirm is kept but flagged — assumed
    // enemy (it's an active targeted spell) but not certain, so the gap is auditable, never invented.
    if (detail.targetResistanceReductionFlat != null && !detail.resistanceTargetEnemyConfirmed) {
        missing += Spell.RESISTANCE_TARGET_UNCERTAIN_FLAG
    }
    return Spell(
        id = stub.id,
        clazz = ref.clazz,
        name = nameOf(stub, frNames, detail.name),
        element = detail.element,
        category = detail.category,
        apCost = detail.apCost,
        wpCost = detail.wpCost,
        rangeMin = detail.rangeMin,
        rangeMax = detail.rangeMax,
        baseDamage = detail.baseDamage,
        critDamage = detail.critDamage,
        area = detail.area,
        requiresLineOfSight = detail.requiresLineOfSight,
        levelRequired = null,
        cooldown = null,
        iconId = stub.iconId,
        description = detail.description?.let { I18nText(fr = it, en = it, es = it, pt = it) },
        targetResistanceReductionFlat = detail.targetResistanceReductionFlat,
        missingFields = missing
    )
}

private fun nameOf(
    stub: SpellStub,
    frNames: Map<Int, String>,
    detailName: String? = null,
): I18nText {
    val en = (detailName ?: stub.name).ifBlank { stub.name }
    val fr = frNames[stub.id]?.ifBlank { en } ?: en
    return I18nText(fr = fr, en = en, es = en, pt = en)
}

private data class ClassReport(
    val clazz: me.chosante.common.CharacterClass,
    val total: Int,
    val withDamage: Int,
    val incompleteCount: Int,
    val incomplete: List<String>,
)

private fun printReport(
    spells: List<Spell>,
    classReports: List<ClassReport>,
    outputFile: File,
) {
    println("\n==================== SPELL DATASET REPORT ====================")
    println("Total spells: ${spells.size} across ${classReports.size} classes")
    val damage = spells.count { it.hasDamage }
    println("With complete numeric damage (element + base damage): $damage")
    println("Elementary spells missing ≥1 expected field: ${spells.count { it.missingFields.isNotEmpty() }}")
    println("\nPer class (total / with-damage / incomplete):")
    classReports.forEach { r ->
        println("  ${r.clazz.name.padEnd(11)} ${r.total.toString().padStart(3)} / ${r.withDamage.toString().padStart(3)} / ${r.incompleteCount}")
    }
    val debuffs = spells.filter { it.isResistanceDebuff }
    println("\nActive resistance-debuff spells (flat all-element reduction): ${debuffs.size}")
    debuffs.forEach { spell ->
        val uncertain = if ("resistanceTarget?" in spell.missingFields) " (target unconfirmed)" else ""
        println("  - [${spell.clazz.name}] ${spell.name.en}: -${spell.targetResistanceReductionFlat} flat res, ${spell.apCost} AP$uncertain")
    }

    val allIncomplete = classReports.flatMap { it.incomplete }
    if (allIncomplete.isNotEmpty()) {
        println("\nSpells with missing/unreadable fields (no value invented):")
        allIncomplete.forEach { println("  - $it") }
    }
    println("\nWritten: ${outputFile.absolutePath} (${outputFile.length() / 1024} KB)")
    println("=============================================================")
}
