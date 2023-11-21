package me.chosante.autobuilder.genetic.wakfu

enum class ScoreComputationMode(val marketingName: String) {
    FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT("most-masteries"), FIND_CLOSEST_BUILD_FROM_INPUT("precision");

    companion object {
        fun from(marketingName: String): ScoreComputationMode? {
            return entries.firstOrNull { it.marketingName == marketingName }
        }
    }
}
