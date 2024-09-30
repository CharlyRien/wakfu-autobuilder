package me.chosante.events

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.eventbus.Event

class AutobuildStartSearchEvent : Event()

class AutobuildEndSearchEvent : Event()

class AutobuildCancelSearchEvent : Event()

class AutobuildUpdateSearchEvent(
    val buildCombination: GeneticAlgorithmResult<BuildCombination>,
) : Event()
