package me.chosante.autobuilder.genetic

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.tongfei.progressbar.ConsoleProgressBarConsumer
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Executors
import kotlin.time.Duration

class GeneticAlgorithm<T>(
    var population: Collection<T>,
    val score: (individual: T) -> Double,
    val cross: (parents: Pair<T, T>) -> T,
    val mutate: (individual: T) -> T,
    val select: (scoredPopulation: Collection<ScoredIndividual<T>>) -> T
) {
    suspend fun run(
        duration: Duration,
        stopWhenBuildMatch: Boolean
    ): T = coroutineScope {
        var scoredPopulation =
            population
                .map { async { ScoredIndividual(score(it), it) } }
                .awaitAll()
                .sortedByDescending { it.score }
        var bestOfAllTimeIndividual = scoredPopulation.first()
        progressBar(duration).use { progressBar ->
            var isActive = true
            launch(progressBarThreadPool) { updateProgressBar(progressBar) { isActive } }
            val startTime = System.currentTimeMillis()
            while (!shouldStop(startTime, duration, bestOfAllTimeIndividual.score, stopWhenBuildMatch)) {
                scoredPopulation = generateNewPopulation(scoredPopulation)
                bestOfAllTimeIndividual = findBestIndividual(scoredPopulation, bestOfAllTimeIndividual)
                val scoreRounded = bestOfAllTimeIndividual.score.toBigDecimal().setScale(2, RoundingMode.FLOOR)
                progressBar.setExtraMessage("$scoreRounded% match found so far")
            }
            isActive = false
            bestOfAllTimeIndividual.individual
        }
    }

    private fun findBestIndividual(
        scoredPopulation: List<ScoredIndividual<T>>,
        bestOfAllTimeIndividual: ScoredIndividual<T>
    ): ScoredIndividual<T> {
        val bestGenerationalIndividual = scoredPopulation.first()
        return if (bestGenerationalIndividual.score.toBigDecimal() > bestOfAllTimeIndividual.score.toBigDecimal()) {
            bestGenerationalIndividual
        } else {
            bestOfAllTimeIndividual
        }
    }

    private fun progressBar(duration: Duration): ProgressBar {
        return ProgressBarBuilder()
            .hideEta()
            .setTaskName("Best build research...")
            .setInitialMax(duration.inWholeSeconds)
            .setStyle(ProgressBarStyle.UNICODE_BLOCK)
            .setConsumer(ConsoleProgressBarConsumer(System.err))
            .build()
    }

    private suspend fun updateProgressBar(progressBar: ProgressBar, isActive: () -> Boolean) {
        while (isActive()) {
            delay(1000L)
            progressBar.stepTo(progressBar.totalElapsed.seconds)
        }
    }


    private fun shouldStop(
        startTime: Long,
        duration: Duration,
        bestScore: Double,
        stopWhenBuildMatch: Boolean
    ): Boolean {
        val maxSuccessPercentage = BigDecimal("100")
        val timeElapsed = System.currentTimeMillis() - startTime
        return (bestScore.toBigDecimal() >= maxSuccessPercentage && stopWhenBuildMatch) || timeElapsed >= duration.inWholeMilliseconds
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

val progressBarThreadPool = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

data class ScoredIndividual<T>(val score: Double, val individual: T)


