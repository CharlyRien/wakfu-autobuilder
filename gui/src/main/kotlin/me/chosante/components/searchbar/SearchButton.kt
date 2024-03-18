package me.chosante.components.searchbar

import atlantafx.base.theme.Styles
import generated.I18nKey
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
import me.chosante.i18n.I18n
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class SearchButton(
    private val getParams: () -> WakfuBestBuildParams,
) : CoroutineScope, Button(I18n.valueOf(I18nKey.SEARCH_BUTTON), FontIcon(Feather.SEARCH)) {

    init {
        styleClass.addAll(Styles.LARGE, Styles.ACCENT, Styles.TITLE_1)
        isDefaultButton = true
        maxWidth = Double.MAX_VALUE
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
                    alert(Alert.AlertType.ERROR, headerText = I18n.valueOf(I18nKey.SEARCH_ALERT_NO_VALUE_SET_HEADER), I18n.valueOf(I18nKey.SEARCH_ALERT_NO_VALUE_SET_CONTENT))
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
