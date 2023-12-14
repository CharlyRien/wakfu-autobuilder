package me.chosante

import atlantafx.base.theme.NordDark
import atlantafx.base.theme.Styles
import atlantafx.base.util.Animations
import java.nio.file.Files
import java.nio.file.Paths
import javafx.application.Application
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Accordion
import javafx.scene.control.Label
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
import me.chosante.autobuilder.domain.Character
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.components.accordion.BuildParamsBox
import me.chosante.components.accordion.CharacteristicTable
import me.chosante.components.accordion.SkillsTable
import me.chosante.components.buildviewer.BuildViewer
import me.chosante.components.searchbar.SearchBox
import me.chosante.eventbus.DefaultEventBus.subscribe
import me.chosante.eventbus.Listener
import me.chosante.events.BrowseEvent

fun main(args: Array<String>) {
    Application.launch(WakfuAutobuilderGUI::class.java, *args)
}

class WakfuAutobuilderGUI : Application(), CoroutineScope {

    private val searchBox = SearchBox(::buildParams, ::getCharacter)
    private val buildParamsBox = BuildParamsBox()
    private val characteristicsTable = CharacteristicTable(::getCharacter)
    private val skillsTable = SkillsTable()
    private val disclaimerLabel = HBox(
        Label("WAKFU est un MMORPG édité par Ankama. \"Wakfu-Autobuilder\" est une application non officielle, sans aucun lien avec Ankama.").apply {
            style = "-fx-font-size: 12px; -fx-text-fill: #696969;"
        }
    ).apply { alignment = Pos.CENTER }

    private val tableAndSettingsAccordion = Accordion(
        TitledPane("Build Parameters", buildParamsBox),
        TitledPane("Characteristics", characteristicsTable),
        TitledPane("Skills", skillsTable)
    ).apply {
        styleClass.addAll(Styles.INTERACTIVE)
        expandedPane = panes.firstOrNull { it.text == "Characteristics" }
    }

    private val settingsAndBuildViewer = SplitPane(
        tableAndSettingsAccordion,
        BuildViewer()
    ).apply {
        orientation = Orientation.HORIZONTAL
        setDividerPositions(0.40)
    }

    private val borderPane = BorderPane().apply {
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
        return WakfuBestBuildParams(
            character = Character(character.clazz, character.level, character.minLevel),
            targetStats = characteristicsTable.targetStats,
            searchDuration = buildParamsBox.searchDuration?.seconds ?: 30.seconds,
            stopWhenBuildMatch = buildParamsBox.stopWhenBuildMatch,
            maxRarity = buildParamsBox.maxRarity,
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
                scene = Scene(rootNode).apply {
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
            Animations.fadeOut(SplashScreen, Duration.seconds(1.0))
                .apply {
                    playFromStart()
                    setOnFinished {
                        rootNode.children -= SplashScreen
                        rootNode.children += borderPane
                    }
                }
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
