package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.chosante.autobuilder.domain.TargetStats

// Helpers of the max-damage E8 construct rescue ([WakfuBuildSolver.dpConstructProvenOptimum]): the row gate that
// decides which requests it may serve, and the bounded / cancellable collection of its CP-SAT re-solves.

/**
 * Whether [targetStats] leaves the max-damage problem a FREE one — the only shape the AP-cell certificate bounds,
 * hence the only one [WakfuBuildSolver.dpConstructProvenOptimum] may declare optimal.
 *
 * Max-damage maximizes the rotation's real damage; the request's rows reach its model ONLY as constraints, through
 * the [isRequiredMostMasteriesTarget] filter — in the solver's hard `actual ≥ target` leg
 * ([StatBuilder.addRequiredTargetHardConstraints]), its shortfall penalty (`applyConstraintPenalty`) and the scorer's
 * mirror ([FindMaxDamageScoring.requiredConstraintPenaltyFactor]). So:
 *  - a **maximized-mastery** row ([isMaximizableMastery]: distance / critical / back / melee / berserk / healing /
 *    elemental — the GUI's default "distance mastery 1", the CLI's `--mastery-distance`) is ignored by the objective
 *    and constrains nothing: with it the problem — and the certificate's ledger — are exactly the free one (locked by
 *    `E8ConstructGateTest`, which compares both), so a build reaching the ledger's bound is optimal for the request;
 *  - a **required** row with a non-zero target (AP / MP / range / crit / HP / resistance / DI …) constrains the build
 *    (a hard leg, or the power-6 penalty). The DP bounds the damage over ALL builds and cannot model that, so a build
 *    reaching its bound may still miss the target — [WakfuBuildSolver.dpConstructProvenOptimum] must refuse. A
 *    0-valued row is a FLOOR (`actual ≥ 0`, [TargetStats.hasFloors]) and stays admitted: the ledger ignores it (a
 *    relaxation — its bound still caps every floored build), and the construct's re-solves run the hard leg, so the
 *    build they return meets it; a build reaching the bound under the floors is then the floored optimum.
 *
 * Also refused, to stay conservative where the argument above is not established:
 *  - rows that trigger the multi-element item prefilter ([WakfuBuildSolver.needsItemPrefilter], e.g. an aggregate
 *    elemental mastery or two elemental rows): the ledger and the re-solve would both run on a HEURISTICALLY reduced
 *    pool, whose optimum proves nothing globally (the reason `MaxDamageSearch.proveOptimality` withholds its badge);
 *  - random-element stats with a target (not exposed by the CLI / GUI; neither maximized nor required).
 */
internal fun isFreeMaxDamageShape(targetStats: TargetStats): Boolean =
    !WakfuBuildSolver.needsItemPrefilter(targetStats) &&
        targetStats.none { it.target != 0 && !it.characteristic.isMaximizableMastery() }

/** How often (ms) [collectWithinBudget] polls its `isCancelled` hook while a flow is being collected. */
internal const val CANCEL_POLL_MILLIS = 100L

/** Why a [collectWithinBudget] collection ended. */
internal enum class CollectEnd {
    /** The flow completed by itself — [BoundedCollection.items] is everything it emitted. */
    COMPLETED,

    /** The wall-clock budget ran out first — [BoundedCollection.items] holds what had been emitted by then. */
    TIMED_OUT,

    /** The caller's `isCancelled` flipped first — [BoundedCollection.items] holds what had been emitted by then. */
    CANCELLED,
}

/** The (possibly partial) emissions of a [collectWithinBudget] collection and how it ended. */
internal class BoundedCollection<T>(
    val items: List<T>,
    val end: CollectEnd,
)

/**
 * Collects [flow], giving up at the wall-clock [budgetMillis] (null = uncapped) or as soon as [isCancelled] flips
 * true (polled every [pollMillis]), and returns whatever the flow had emitted until then. Leaving the collection
 * cancels the flow, which tears down the native CP-SAT solve behind [WakfuBuildSolver.optimize] (its `awaitClose` →
 * `stopSearch`) — coroutine cancellation alone cannot interrupt that solve. A flow that throws propagates its
 * exception, like `toList()` would. Partial emissions are returned so the caller can still judge them with its own
 * (sound) acceptance rule instead of losing a build that was found just before the deadline.
 */
internal suspend fun <T> collectWithinBudget(
    flow: Flow<T>,
    budgetMillis: Long?,
    isCancelled: () -> Boolean = { false },
    pollMillis: Long = CANCEL_POLL_MILLIS,
): BoundedCollection<T> {
    val collected = ArrayList<T>()
    if (budgetMillis != null && budgetMillis <= 0L) return BoundedCollection(collected, CollectEnd.TIMED_OUT)
    if (isCancelled()) return BoundedCollection(collected, CollectEnd.CANCELLED)
    var end = CollectEnd.COMPLETED

    suspend fun collectUntilDoneOrCancelled() =
        coroutineScope {
            val collector = launch { flow.collect { collected += it } }
            while (true) {
                // join() returns only once the collector completed; the inner timeout turns it into a poll tick.
                if (withTimeoutOrNull(pollMillis) { collector.join() } != null) break
                if (isCancelled()) {
                    end = CollectEnd.CANCELLED
                    collector.cancelAndJoin()
                    break
                }
            }
        }

    val inTime =
        if (budgetMillis == null) {
            collectUntilDoneOrCancelled()
            true
        } else {
            withTimeoutOrNull(budgetMillis) {
                collectUntilDoneOrCancelled()
                true
            }
        }
    return BoundedCollection(collected, if (inTime == null) CollectEnd.TIMED_OUT else end)
}
