package me.chosante.autobuilder.genetic

import kotlin.math.max
import kotlin.random.Random

internal fun <T> tournamentSelection(
    scoredPopulation: Collection<ScoredIndividual<T>>,
    random: Random = Random.Default,
): T {
    val tournamentSize = max(scoredPopulation.size / 100, 2)
    val (firstRandomScore, firstRandomIndividual) = scoredPopulation.elementAt(random.nextInt(scoredPopulation.size))
    var bestIndividualInTournament = firstRandomIndividual
    var bestScoreInTournament = firstRandomScore
    repeat(tournamentSize - 1) {
        val randomIndice = random.nextInt(scoredPopulation.size)
        val (score, individual) = scoredPopulation.elementAt(randomIndice)
        if (score > bestScoreInTournament) {
            bestIndividualInTournament = individual
            bestScoreInTournament = score
        }
    }

    return bestIndividualInTournament
}
