package me.chosante.components.searchbar

import atlantafx.base.controls.RingProgressIndicator
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.event.EventHandler
import javafx.scene.control.Button
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Tooltip
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.Character
import me.chosante.autobuilder.zenithbuilder.createZenithBuild
import me.chosante.eventbus.DefaultEventBus.publish
import me.chosante.eventbus.DefaultEventBus.subscribe
import me.chosante.eventbus.Listener
import me.chosante.events.AutobuildEndSearchEvent
import me.chosante.events.AutobuildStartSearchEvent
import me.chosante.events.AutobuildUpdateSearchEvent
import me.chosante.events.ZenithBuildCreatedEvent

@Suppress("UNUSED_PARAMETER")
class ZenithWakfuBuildButton(
    getCharacter: () -> Character,
) : Button("Create Zenith Wakfu Build"), CoroutineScope {

    private val lastBuildFound = SimpleObjectProperty<BuildCombination>()
    private val isZenithWakfuCreationInProgress = SimpleBooleanProperty(false)
    private val zenithBuildCreationProgressIndicator = RingProgressIndicator(-1.0, false)

    init {
        disableProperty().bind(lastBuildFound.isNull.or(isZenithWakfuCreationInProgress))
        tooltip = Tooltip("Create Zenith Wakfu Build")
        contentDisplay = ContentDisplay.RIGHT
        onMouseClicked = EventHandler {
            if (!isDisabled) {
                val buildCombination = lastBuildFound
                if (buildCombination.isNotNull.value) {
                    launch {
                        isZenithWakfuCreationInProgress.value = true
                        // little hack here to be on the javafx thread to update the UI
                        graphicProperty().value = zenithBuildCreationProgressIndicator
                        val createZenithBuildUrl = buildCombination.get().createZenithBuild(getCharacter())
                        graphicProperty().value = null
                        publish(ZenithBuildCreatedEvent(createZenithBuildUrl))
                    }.invokeOnCompletion { isZenithWakfuCreationInProgress.value = false }
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
