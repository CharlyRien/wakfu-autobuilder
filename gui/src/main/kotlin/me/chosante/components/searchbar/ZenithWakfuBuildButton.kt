package me.chosante.components.searchbar

import atlantafx.base.controls.RingProgressIndicator
import generated.I18nKey
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.event.EventHandler
import javafx.scene.control.Button
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Tooltip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import me.chosante.ZenithInputParameters
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.common.Character
import me.chosante.createZenithBuild
import me.chosante.eventbus.DefaultEventBus.publish
import me.chosante.eventbus.DefaultEventBus.subscribe
import me.chosante.eventbus.Listener
import me.chosante.events.AutobuildEndSearchEvent
import me.chosante.events.AutobuildStartSearchEvent
import me.chosante.events.AutobuildUpdateSearchEvent
import me.chosante.events.ZenithBuildCreatedEvent
import me.chosante.i18n.I18n
import kotlin.coroutines.CoroutineContext

@Suppress("UNUSED_PARAMETER")
class ZenithWakfuBuildButton(
    getCharacter: () -> Character,
) : Button(I18n.valueOf(I18nKey.ZENITH_WAKFU_BUILD_CREATION_BUTTON)),
    CoroutineScope {
    private val lastBuildFound = SimpleObjectProperty<BuildCombination>()
    private val isZenithWakfuCreationInProgress = SimpleBooleanProperty(false)
    private val zenithBuildCreationProgressIndicator = RingProgressIndicator(-1.0, false)

    init {
        disableProperty().bind(lastBuildFound.isNull.or(isZenithWakfuCreationInProgress))
        tooltip = Tooltip(I18n.valueOf(I18nKey.ZENITH_WAKFU_BUILD_CREATION_BUTTON))
        contentDisplay = ContentDisplay.RIGHT
        onMouseClicked =
            EventHandler {
                if (!isDisabled) {
                    val buildCombination = lastBuildFound
                    if (buildCombination.isNotNull.value) {
                        launch {
                            isZenithWakfuCreationInProgress.value = true
                            graphicProperty().value = zenithBuildCreationProgressIndicator
                            val combination = buildCombination.get()
                            val zenithBuildUrl =
                                ZenithInputParameters(
                                    character = getCharacter().copy(characterSkills = combination.characterSkills),
                                    equipments = combination.equipments
                                ).createZenithBuild()
                            publish(ZenithBuildCreatedEvent(zenithBuildUrl))
                        }.invokeOnCompletion {
                            isZenithWakfuCreationInProgress.value = false
                            graphicProperty().value = null
                        }
                    }
                }
            }
        subscribe(AutobuildUpdateSearchEvent::class, ::onBuildUpdate)
        subscribe(AutobuildStartSearchEvent::class, ::onBuildStart)
        subscribe(AutobuildEndSearchEvent::class, ::onBuildEnd)
    }

    @Listener
    private fun onBuildUpdate(event: AutobuildUpdateSearchEvent) {
        lastBuildFound.value = event.buildCombination.individual
    }

    @Listener
    private fun onBuildStart(event: AutobuildStartSearchEvent) {
        isZenithWakfuCreationInProgress.value = true
    }

    @Listener
    private fun onBuildEnd(event: AutobuildEndSearchEvent) {
        isZenithWakfuCreationInProgress.value = false
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.JavaFx
}
