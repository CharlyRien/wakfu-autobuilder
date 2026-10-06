package me.chosante.autobuilder.domain

import me.chosante.autobuilder.EmbeddedResources
import me.chosante.common.I18nText

/** Display-only names: achievement requirements remain assumed satisfied by the engine. */
object AchievementCatalog {
    val names: Map<Int, I18nText> by lazy { EmbeddedResources.decode<Map<Int, I18nText>>("achievement-names.json").orEmpty() }
}
