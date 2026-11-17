package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.RuneType
import me.chosante.common.Sublimation

/**
 * The RAW outputs of every certifier tier, run serially on ONE [StatBuilder] — what the dead-label filter
 * ([CertifierTuning.deadLabelFilterEnabled]) must leave identical: every cell bound, every per-crit-step row, the argmax
 * provenance. [entries] keeps them labelled, in run order; [nanos] / [adds] are the work of each tier and [refused] the
 * states the filter refused there (0 with the filter off).
 */
internal class CertTranscript {
    val entries = ArrayList<Pair<String, LongArray>>()
    val nanos = LinkedHashMap<String, Long>()
    val adds = LinkedHashMap<String, Long>()
    val refused = LinkedHashMap<String, Long>()
    val values: Long get() = entries.sumOf { it.second.size.toLong() }

    fun put(
        label: String,
        v: LongArray,
    ) {
        entries += label to v
    }

    /** The first differing entry against [other], or null when both transcripts are identical (labels, order, contents). */
    fun firstDifference(other: CertTranscript): String? {
        if (entries.size != other.entries.size) return "entry count ${entries.size} vs ${other.entries.size}"
        for (i in entries.indices) {
            val (la, va) = entries[i]
            val (lb, vb) = other.entries[i]
            if (la != lb) return "entry $i label '$la' vs '$lb'"
            if (!va.contentEquals(vb)) {
                val at = (0 until minOf(va.size, vb.size)).firstOrNull { va[it] != vb[it] }
                return "entry '$la' differs (sizes ${va.size} / ${vb.size}, first index $at: ${at?.let { va[it] }} vs ${at?.let { vb[it] }})"
            }
        }
        return null
    }
}

/**
 * Runs every tier serially on this StatBuilder and records its raw outputs:
 *  - tier 1: [certifyAllCellsFast] (every cell, plus each world's per-(cell, crit-step) rows), then the aux floor (the
 *    exact aux values and the relaxed capped world against its split — the cache is cleared first and after);
 *  - tier 1.5 on [tier15Cells]: values and per-(cell, world) rows, unskipped, then (when [skipPercent] is set) with the
 *    production segment skip against a threshold of that percentage of the cell's own tier-1.5 value;
 *  - the cascade's exact-first probe on the first of [exactCells];
 *  - exact on [exactCells]: pruned by the tier-1.5 rows as production does (values, argmax (world, crit step), and the
 *    per-crit-step maxima of every step that ran, in run order), then UNPRUNED on [unprunedCells] (every crit step).
 * The cell lists are functions of the tier-1 raw bounds so both arms pick the same cells.
 */
internal fun StatBuilder.certifierTranscript(
    scenario: DamageScenario,
    cellCount: Int,
    tier15Cells: (LongArray) -> List<Int>,
    exactCells: (LongArray) -> List<Int>,
    unprunedCells: (LongArray) -> List<Int> = { emptyList() },
    skipPercent: Int? = 98,
): CertTranscript {
    val t = CertTranscript()

    fun <T> timed(
        tier: String,
        block: () -> T,
    ): T {
        val t0 = System.nanoTime()
        val a0 = Frontier.statsAddCalls
        val r0 = CertifierTuning.deadLabelsRefusedForTest.get()
        val r = block()
        t.nanos.merge(tier, System.nanoTime() - t0, Long::plus)
        t.adds.merge(tier, Frontier.statsAddCalls - a0, Long::plus)
        t.refused.merge(tier, CertifierTuning.deadLabelsRefusedForTest.get() - r0, Long::plus)
        return r
    }
    val worlds = certifierWorlds(scenario)
    if (worlds == null) {
        t.put("no-worlds", LongArray(0))
        return t
    }
    val rows1 = arrayOfNulls<Array<LongArray>>(worlds.size)
    val fast = timed("tier1") { certifyAllCellsFast(scenario, cellCount, 1, perCellCByWorldOut = rows1, applyAux = false) }
    t.put("tier1.cells", fast)
    for (wi in worlds.indices) {
        val rows = rows1[wi]
        if (rows == null) {
            t.put("tier1.rows.w$wi.absent", LongArray(0))
        } else {
            for (a in rows.indices) t.put("tier1.rows.w$wi.a$a", rows[a])
        }
    }
    synchronized(certifierAuxFloorCache) { certifierAuxFloorCache.clear() }
    timed("aux") {
        val aux = certifierAuxFloor(scenario, cellCount)
        if (aux == null) {
            t.put("aux.none", LongArray(0))
        } else {
            t.put("aux.exact", aux.exactForTest(1))
            aux.relaxedVsSplitForTest(1)?.let { (relaxed, split) ->
                t.put("aux.relaxed", relaxed)
                t.put("aux.split", split)
            }
        }
    }
    synchronized(certifierAuxFloorCache) { certifierAuxFloorCache.clear() }
    if (fast.any { it == Long.MAX_VALUE }) return t

    val cells15 = tier15Cells(fast)
    val t15 = timed("tier15") { certifyCellsTier15(scenario, cells15, 1, applyAux = false) }
    for (a in cells15) {
        t.put("tier15.a$a", longArrayOf(t15.values[a] ?: Long.MIN_VALUE))
        val rows = t15.perCByCell[a]
        for (wi in worlds.indices) t.put("tier15.rows.a$a.w$wi", rows?.getOrNull(wi) ?: LongArray(0))
    }
    if (skipPercent != null && rows1.all { it != null }) {
        val thresholds = cells15.associateWith { a -> (t15.values[a] ?: 0L).let { if (it == Long.MAX_VALUE) Long.MIN_VALUE else it / 100L * skipPercent } }
        val skipped = timed("tier15skip") { certifyCellsTier15(scenario, cells15, 1, skipUbByWorld = rows1, skipBelowRawByCell = thresholds, applyAux = false) }
        for (a in cells15) {
            t.put("tier15skip.a$a", longArrayOf(skipped.values[a] ?: Long.MIN_VALUE))
            val rows = skipped.perCByCell[a]
            for (wi in worlds.indices) t.put("tier15skip.rows.a$a.w$wi", rows?.getOrNull(wi) ?: LongArray(0))
        }
    }

    val cellsX = exactCells(fast)
    cellsX.firstOrNull()?.let { a ->
        if (rows1.all { it != null }) {
            val tier1Rows = Array(worlds.size) { wi -> rows1[wi]?.getOrNull(a) }
            val probe = timed("probe") { probeTopWorld(scenario, a, tier1Rows) }
            t.put("probe.a$a", if (probe == null) LongArray(0) else longArrayOf(probe.first.toLong(), probe.second[0], probe.second[1]))
        }
    }

    fun exactRun(
        tier: String,
        cells: List<Int>,
        ub: Map<Int, Array<LongArray?>>?,
    ) {
        if (cells.isEmpty()) return
        val perC = ArrayList<Long>()
        val prov = LinkedHashMap<Int, CellProvenance>()
        val previous = CertifierTuning.exactPerCObserverForTest
        CertifierTuning.exactPerCObserverForTest = { ap, c, v ->
            perC += ap.toLong()
            perC += c.toLong()
            perC += v
        }
        val values =
            try {
                timed(tier) { exactForCells(scenario, cells, 1, provOut = prov, ubByWorldCell = ub, applyAux = false) }
            } finally {
                CertifierTuning.exactPerCObserverForTest = previous
            }
        for (a in cells) {
            val p = prov[a]
            t.put("$tier.a$a", longArrayOf(values[a] ?: Long.MIN_VALUE, (p?.worldIndex ?: -1).toLong(), (p?.c ?: -1).toLong()))
        }
        t.put("$tier.perCritStep(ap,c,max)", perC.toLongArray())
    }
    exactRun("exact", cellsX, t15.perCByCell)
    exactRun("exactUnpruned", unprunedCells(fast), null)
    return t
}

/**
 * Builds the max-damage model for these params and hands its [StatBuilder] to [block] at the certificate seam
 * ([CertifierTuning.ledgerHookForTest]) — once, on the calling thread.
 */
internal fun withCertifierStatBuilder(
    params: WakfuBestBuildParams,
    pool: Map<ItemType, List<Equipment>>,
    runes: List<RuneType>,
    subs: List<Sublimation>,
    applyDomination: Boolean,
    targetAware: Boolean,
    block: StatBuilder.(DamageScenario, LongArray, Long) -> Unit,
): Boolean {
    var ran = false
    val previous = CertifierTuning.ledgerHookForTest
    CertifierTuning.ledgerHookForTest = { scenario, table, resFactor ->
        ran = true
        block(scenario, table, resFactor)
        CertLedger(emptyMap(), emptySet(), emptySet(), null)
    }
    try {
        WakfuBuildSolver.certifyLedgerForTest(params, pool, runes, subs, applyDomination = applyDomination, threads = 1, targetAware = targetAware)
    } finally {
        CertifierTuning.ledgerHookForTest = previous
    }
    return ran
}

/** Both arms of the dead-label filter on the same StatBuilder: `(off, on)` transcripts. Restores the seam. */
internal fun StatBuilder.deadLabelArms(run: StatBuilder.() -> CertTranscript): Pair<CertTranscript, CertTranscript> {
    val was = CertifierTuning.deadLabelFilterEnabled
    try {
        CertifierTuning.deadLabelFilterEnabled = false
        val off = run()
        CertifierTuning.deadLabelFilterEnabled = true
        val on = run()
        return off to on
    } finally {
        CertifierTuning.deadLabelFilterEnabled = was
    }
}
