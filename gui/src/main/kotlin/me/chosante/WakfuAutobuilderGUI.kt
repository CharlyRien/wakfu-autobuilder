package me.chosante

import atlantafx.base.theme.NordDark
import atlantafx.base.theme.Styles
import generated.I18nKey
import java.nio.file.Files
import java.nio.file.Paths
import javafx.animation.FadeTransition
import javafx.application.Application
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Accordion
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.SplitPane
import javafx.scene.control.TitledPane
import javafx.scene.image.Image
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.util.Duration
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.common.Character
import me.chosante.components.accordion.BuildParamsBox
import me.chosante.components.accordion.CharacteristicTable
import me.chosante.components.accordion.ItemsForcedTable
import me.chosante.components.accordion.SkillsTable
import me.chosante.components.buildviewer.BuildViewer
import me.chosante.components.searchbar.SearchBox
import me.chosante.eventbus.DefaultEventBus.subscribe
import me.chosante.eventbus.Listener
import me.chosante.events.BrowseEvent
import me.chosante.i18n.I18n

fun main(args: Array<String>) {
    Application.launch(WakfuAutobuilderGUI::class.java, *args)
}

class WakfuAutobuilderGUI :
    Application(),
    CoroutineScope {
    private val searchBox = SearchBox(::buildParams, ::getCharacter)
    private val buildParamsBox = BuildParamsBox()
    private val characteristicsTable = CharacteristicTable(::getCharacter)
    private val skillsTable = SkillsTable()
    private val itemForcedTable = ItemsForcedTable()
    private val disclaimerLabel =
        HBox(
            Label(I18n.valueOf(I18nKey.WAKFU_DISCLAIMER_LABEL)).apply {
                style = "-fx-font-size: 12px; -fx-text-fill: #696969;"
            }
        ).apply { alignment = Pos.CENTER }

    private val tableAndSettingsAccordion =
        Accordion(
            TitledPane(
                I18n.valueOf(I18nKey.BUILD_PARAMETERS_PANE_TITLE),
                ScrollPane(buildParamsBox).apply {
                    isFitToWidth = true
                    minHeight = 200.0
                }
            ),
            TitledPane(I18n.valueOf(I18nKey.CHARACTERISTICS_PANE_TITLE), characteristicsTable),
            TitledPane(I18n.valueOf(I18nKey.SKILLS_PANE_TITLE), skillsTable),
            TitledPane(I18n.valueOf(I18nKey.ITEM_FORCED_PANE_TITLE), itemForcedTable)
        ).apply {
            styleClass.addAll(Styles.INTERACTIVE)
            expandedPane = panes.firstOrNull { it.text == I18n.valueOf(I18nKey.CHARACTERISTICS_PANE_TITLE) }
        }

    private val settingsAndBuildViewer =
        SplitPane(
            tableAndSettingsAccordion,
            BuildViewer()
        ).apply {
            orientation = Orientation.HORIZONTAL
            setDividerPositions(0.40)
        }

    private val borderPane =
        BorderPane().apply {
            opacity = 0.0
            center = settingsAndBuildViewer
            top = searchBox
            bottom = disclaimerLabel
            BorderPane.setMargin(disclaimerLabel, Insets(5.0))
            BorderPane.setMargin(settingsAndBuildViewer, Insets(15.0))
            BorderPane.setMargin(searchBox, Insets(15.0))
        }

    private fun getCharacter() = buildParamsBox.character

    private fun buildParams(): WakfuBestBuildParams {
        val character = buildParamsBox.character
        val scoreComputationMode =
            if (buildParamsBox.precisionMode) {
                ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
            } else {
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
            }
        return WakfuBestBuildParams(
            character = Character(character.clazz, character.level, character.minLevel),
            targetStats = characteristicsTable.targetStats,
            searchDuration = buildParamsBox.searchDuration?.seconds ?: 30.seconds,
            stopWhenBuildMatch = buildParamsBox.stopWhenBuildMatch,
            maxRarity = buildParamsBox.maxRarity,
            scoreComputationMode = scoreComputationMode,
            forcedItems = listOf(),
            excludedItems = listOf()
        )
    }

    /*
     * Used to load icon resized by Conveyor
     */
    private fun loadIconsForStage(stage: Stage) {
        val appDir = System.getProperty("app.dir") ?: return
        val iconsDir = Paths.get(appDir)
        Files.newDirectoryStream(iconsDir, "logo.png").use { dirEntries ->
            for (iconFile in dirEntries) {
                Files.newInputStream(iconFile).use { icon -> stage.icons.add(Image(icon)) }
            }
        }
    }

    override fun start(stage: Stage) {
        launch {
            setUserAgentStylesheet(NordDark().userAgentStylesheet)
            loadIconsForStage(stage)
            val rootNode = StackPane(SplashScreen)

            stage.apply {
                title = "Wakfu autobuilder"
                isMaximized = true
                scene =
                    Scene(rootNode).apply {
                        stylesheets.add("assets/css/root.css")
                    }
                showingProperty().addListener { _, _, newValue ->
                    if (newValue) {
                        settingsAndBuildViewer.setDividerPositions(0.4)
                    }
                }
            }

            stage.show()
            SplashScreen.animateSplashScreen()
            rootNode.children -= SplashScreen
            rootNode.children += borderPane
            FadeTransition(Duration(1000.0), borderPane)
                .apply {
                    fromValue = 0.0
                    toValue = 1.0
                }.awaitPlay()
            subscribe(BrowseEvent::class, ::openBrowser)
        }
    }

    @Listener
    fun openBrowser(browseEvent: BrowseEvent) {
        hostServices.showDocument(browseEvent.uri.toString())
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.JavaFx
}
