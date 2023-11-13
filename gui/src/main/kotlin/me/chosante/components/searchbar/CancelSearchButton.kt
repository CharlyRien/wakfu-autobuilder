package me.chosante.components.searchbar

import atlantafx.base.theme.Styles
import javafx.event.EventHandler
import javafx.scene.control.Button
import me.chosante.eventbus.DefaultEventBus
import me.chosante.eventbus.DefaultEventBus.publish
import me.chosante.eventbus.Listener
import me.chosante.events.AutobuildCancelSearchEvent
import me.chosante.events.AutobuildEndSearchEvent
import me.chosante.events.AutobuildStartSearchEvent
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

@Suppress("UNUSED_PARAMETER")
class CancelSearchButton : Button(null, FontIcon(Feather.X)) {
    init {
        styleClass.addAll(Styles.LARGE, Styles.DANGER)
        isCancelButton = true
        isDisable = true
        onMouseClicked = EventHandler {
            publish(AutobuildCancelSearchEvent())
        }
        DefaultEventBus.subscribe(AutobuildStartSearchEvent::class, ::onSearchStart)
        DefaultEventBus.subscribe(AutobuildEndSearchEvent::class, ::onSearchEnd)
    }

    @Listener
    fun onSearchStart(event: AutobuildStartSearchEvent) {
        isDisable = false
    }

    @Listener
    fun onSearchEnd(event: AutobuildEndSearchEvent) {
        isDisable = true
    }
}
