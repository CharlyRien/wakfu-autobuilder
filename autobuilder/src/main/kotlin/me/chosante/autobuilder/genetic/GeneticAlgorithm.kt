package me.chosante.autobuilder.genetic

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class GeneticAlgorithm<T>(
    var population: Collection<T>,
    val score: (individual: T) -> BigDecimal,
    val cross: (parents: Pair<T, T>) -> T,
    val mutate: (individual: T) -> T,
    val select: (scoredPopulation: Collection<ScoredIndividual<T>>) -> T,
) {
    suspend fun run(
        duration: Duration,
        stopWhenBuildMatch: Boolean,
    ): Flow<GeneticAlgorithmResult<T>> = coroutineScope {
        var scoredPopulation =
            population
                .map { async { ScoredIndividual(score(it), it) } }
                .awaitAll()
                .sortedByDescending { it.score }
        var bestOfAllTimeIndividual = scoredPopulation.first()
        val startTime = System.currentTimeMillis()
        flow {
            while (!shouldStop(startTime, duration, bestOfAllTimeIndividual.score, stopWhenBuildMatch)) {
                scoredPopulation = generateNewPopulation(scoredPopulation)
                bestOfAllTimeIndividual = findBestIndividual(scoredPopulation, bestOfAllTimeIndividual)
                val scoreRounded = bestOfAllTimeIndividual.score.setScale(2, RoundingMode.FLOOR)
                emit(
                    GeneticAlgorithmResult(
                        individual = bestOfAllTimeIndividual.individual,
                        matchPercentage = scoreRounded,
                        progressPercentage = floor(100.0 * Duration.between(startTime..System.currentTimeMillis()).inWholeSeconds / duration.inWholeSeconds).toInt()
                    )
                )
            }
            emit(
                GeneticAlgorithmResult(
                    individual = bestOfAllTimeIndividual.individual,
                    matchPercentage = bestOfAllTimeIndividual.score.setScale(2, RoundingMode.FLOOR),
                    progressPercentage = floor(100.0 * Duration.between(startTime..System.currentTimeMillis()).inWholeSeconds / duration.inWholeSeconds).toInt()
                )
            )
        }
    }

    private fun findBestIndividual(
        scoredPopulation: List<ScoredIndividual<T>>,
        bestOfAllTimeIndividual: ScoredIndividual<T>,
    ): ScoredIndividual<T> {
        val bestGenerationalIndividual = scoredPopulation.first()
        return if (bestGenerationalIndividual.score > bestOfAllTimeIndividual.score) {
            bestGenerationalIndividual
        } else {
            bestOfAllTimeIndividual
        }
    }

    private fun shouldStop(
        startTime: Long,
        duration: Duration,
        bestScore: BigDecimal,
        stopWhenBuildMatch: Boolean,
    ): Boolean {
        val maxSuccessPercentage = BigDecimal("100")
        val timeElapsed = System.currentTimeMillis() - startTime
        return (bestScore >= maxSuccessPercentage && stopWhenBuildMatch) || timeElapsed >= duration.inWholeMilliseconds
    }

    private suspend fun generateNewPopulation(scoredPopulation: List<ScoredIndividual<T>>) = coroutineScope {
        scoredPopulation
            .map {
                async {
                    val selectedParents = select(scoredPopulation) to select(scoredPopulation)
                    val crossedIndividual = cross(selectedParents)
                    val mutatedIndividual = mutate(crossedIndividual)
                    ScoredIndividual(score(mutatedIndividual), mutatedIndividual)
                }
            }
            .awaitAll()
            .sortedByDescending { it.score }
    }
}

private fun Duration.Companion.between(timestampRange: LongRange): Duration {
    val firstInstant = Instant.ofEpochMilli(timestampRange.first)
    val lastInstant = Instant.ofEpochMilli(timestampRange.last)
    return java.time.Duration.between(firstInstant, lastInstant).toKotlinDuration()
}

data class ScoredIndividual<T>(val score: BigDecimal, val individual: T)

data class GeneticAlgorithmResult<T>(
    val individual: T,
    val matchPercentage: BigDecimal,
    val progressPercentage: Int,
)
