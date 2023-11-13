package me.chosante.components.searchbar

import atlantafx.base.theme.Styles
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.Separator
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import me.chosante.autobuilder.domain.Character
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.eventbus.DefaultEventBus
import me.chosante.eventbus.Listener
import me.chosante.events.AutobuildEndSearchEvent
import me.chosante.events.AutobuildStartSearchEvent
import me.chosante.events.AutobuildUpdateSearchEvent
import me.chosante.events.BrowseEvent
import me.chosante.events.ZenithBuildCreatedEvent

@Suppress("UNUSED_PARAMETER")
class SearchBox(
    getParams: () -> WakfuBestBuildParams,
    getCharacter: () -> Character,
) : HBox(5.0), CoroutineScope {

    private var buildUrlHyperlink: Hyperlink? = null

    private val progressBar = ProgressBar(0.0).apply {
        styleClass.add(Styles.LARGE)
    }
    private val matchPercentageLabel = Label("Match Percentage: 0%")
    private val searchButton = SearchButton(getParams)
    private val cancelSearchButton = CancelSearchButton()
    private val createZenithWakfuBuildButton = ZenithWakfuBuildButton(getCharacter)

    private val buildUrlLabel = Label("Zenith Build URL:")

    init {
        children += searchButton
        children += cancelSearchButton
        children += Separator(Orientation.VERTICAL)
        children += progressBar
        children += Separator(Orientation.VERTICAL)
        children += matchPercentageLabel
        children += Separator(Orientation.VERTICAL)
        children += createZenithWakfuBuildButton
        children += Separator(Orientation.VERTICAL)
        children += buildUrlLabel
        alignment = Pos.CENTER
        setHgrow(searchButton, Priority.ALWAYS)
        setHgrow(matchPercentageLabel, Priority.ALWAYS)
        DefaultEventBus.subscribe(AutobuildUpdateSearchEvent::class, ::onBuildProcessUpdate)
        DefaultEventBus.subscribe(AutobuildStartSearchEvent::class, ::onBuildProcessStart)
        DefaultEventBus.subscribe(AutobuildEndSearchEvent::class, ::onBuildProcessEnd)
        DefaultEventBus.subscribe(ZenithBuildCreatedEvent::class, ::onZenithWakfuBuildCreated)
    }

    @Listener
    private fun onBuildProcessUpdate(autobuildUpdateSearchEvent: AutobuildUpdateSearchEvent) {
        launch {
            val buildCombination = autobuildUpdateSearchEvent.buildCombination
            progressBar.progressProperty().value = buildCombination.progressPercentage / 100.0
            matchPercentageLabel.textProperty().value = "Match Percentage: ${buildCombination.individualMatchPercentage}%"
        }
    }

    @Listener
    private fun onZenithWakfuBuildCreated(zenithBuildCreatedEvent: ZenithBuildCreatedEvent) {
        launch {
            buildUrlHyperlink?.let { children.remove(it) }
            buildUrlHyperlink = Hyperlink(zenithBuildCreatedEvent.buildUrl).apply {
                setOnAction {
                    BrowseEvent.fire(zenithBuildCreatedEvent.buildUrl)
                }
            }
            setHgrow(buildUrlHyperlink, Priority.ALWAYS)
            children += buildUrlHyperlink
        }
    }

    @Listener
    private fun onBuildProcessStart(event: AutobuildStartSearchEvent) {
        launch {
            progressBar.progressProperty().value = 0.0
        }
    }

    @Listener
    private fun onBuildProcessEnd(event: AutobuildEndSearchEvent) {
        launch {
            progressBar.progressProperty().value = 1.0
        }
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.JavaFx
}
