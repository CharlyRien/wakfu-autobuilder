package me.chosante.autobuilder.genetic

import kotlin.math.max
import kotlin.random.Random.Default.nextInt

fun <T> tournamentSelection(
    scoredPopulation: Collection<ScoredIndividual<T>>,
): T {
    val tournamentSize = max(scoredPopulation.size / 1000, 2)
    val (firstRandomScore, firstRandomIndividual) = scoredPopulation.elementAt(nextInt(scoredPopulation.size))
    var bestIndividualInTournament = firstRandomIndividual
    var bestScoreInTournament = firstRandomScore
    repeat(tournamentSize - 1) {
        val randomIndice = nextInt(scoredPopulation.size)
        val (score, individual) = scoredPopulation.elementAt(randomIndice)
        if (score > bestScoreInTournament) {
            bestIndividualInTournament = individual
            bestScoreInTournament = score
        }
    }

    return bestIndividualInTournament
}
