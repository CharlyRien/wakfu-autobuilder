package me.chosante.components.searchbar

import atlantafx.base.theme.Styles
import javafx.event.EventHandler
import javafx.scene.control.Alert
import javafx.scene.control.Button
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.javafx.JavaFx
import me.chosante.alert
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.components.AutobuilderComputation
import me.chosante.eventbus.DefaultEventBus
import me.chosante.eventbus.Listener
import me.chosante.events.AutobuildEndSearchEvent
import me.chosante.events.AutobuildStartSearchEvent

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class SearchButton(
    private val getParams: () -> WakfuBestBuildParams,
) : CoroutineScope, Button("Search") {

    init {
        styleClass.addAll(Styles.LARGE, Styles.ACCENT)
        isDefaultButton = true
    }

    @Listener
    private fun processEnd(event: AutobuildEndSearchEvent) {
        isDisabled = false
    }

    @Listener
    private fun processStarted(event: AutobuildStartSearchEvent) {
        isDisabled = true
    }

    init {
        prefWidth = 100.0
        onMouseClicked = EventHandler {
            if (!isDisabled) {
                val wakfuBestBuildParams = getParams()
                if (wakfuBestBuildParams.targetStats.isEmpty()) {
                    alert(Alert.AlertType.ERROR, headerText = "No desired value set", "You should specify at least one desired value for your build")
                    return@EventHandler
                }
                AutobuilderComputation().start(wakfuBestBuildParams = wakfuBestBuildParams)
            }
        }

        DefaultEventBus.subscribe(AutobuildStartSearchEvent::class, ::processStarted)
        DefaultEventBus.subscribe(AutobuildEndSearchEvent::class, ::processEnd)
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.JavaFx
}
