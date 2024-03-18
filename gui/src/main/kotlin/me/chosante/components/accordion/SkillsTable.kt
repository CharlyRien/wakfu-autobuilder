package me.chosante.components.accordion

import atlantafx.base.theme.Styles
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableValue
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeTableColumn
import javafx.scene.control.TreeTableView
import javafx.util.Callback
import kotlin.reflect.KClass
import me.chosante.common.skills.AgilityCharacteristic
import me.chosante.common.skills.IntelligenceCharacteristic
import me.chosante.common.skills.LuckCharacteristic
import me.chosante.common.skills.MajorCharacteristic
import me.chosante.common.skills.SkillCharacteristic
import me.chosante.common.skills.StrengthCharacteristic
import me.chosante.eventbus.DefaultEventBus.subscribe
import me.chosante.eventbus.Listener
import me.chosante.events.AutobuildUpdateSearchEvent

class SkillsTable : TreeTableView<SkillRowContent>() {

    private val classToSkillCurrentValue = mutableMapOf<KClass<out SkillCharacteristic>, SimpleStringProperty>()

    init {
        val col1 = TreeTableColumn<SkillRowContent, String>("Skill name").apply {
            cellValueFactory = Callback { c -> SimpleStringProperty(c.value.value.name) }
            prefWidth = 250.0
        }

        val col2 = TreeTableColumn<SkillRowContent, String>("Value").apply {
            val nestedCol1 = TreeTableColumn<SkillRowContent, String>("Current").apply {
                cellValueFactory = Callback { c -> c.value.value.current }
            }
            val nestedCol2 = TreeTableColumn<SkillRowContent, String>("Max").apply {
                cellValueFactory = Callback { c ->
                    val value = c.value.value
                    SimpleStringProperty(if (value.max.toIntOrNull() == Int.MAX_VALUE) "∞" else value.max)
                }
            }
            columns.addAll(nestedCol1, nestedCol2)
        }

        columns.addAll(col1, col2)
        columnResizePolicy = CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        styleClass.addAll(Styles.BORDERED, Styles.STRIPED)
        root = initTree()
        isShowRoot = false

        subscribe(AutobuildUpdateSearchEvent::class, ::onBuildUpdate)
    }

    private fun initTree(): TreeItem<SkillRowContent> {
        return TreeItem(SkillRowContent("dummy", SimpleStringProperty(""), ""))
            .apply {
                children.addAll(
                    TreeItem(SkillRowContent("Agility", SimpleStringProperty(""), "")).apply {
                        children.setAll(
                            AgilityCharacteristic.Dodge(0).toTreeItem(),
                            AgilityCharacteristic.Lock(0).toTreeItem(),
                            AgilityCharacteristic.Initiative(0).toTreeItem(),
                            AgilityCharacteristic.Willpower(0).toTreeItem(),
                            AgilityCharacteristic.DodgeAndLock(0).toTreeItem()
                        )
                        isExpanded = true
                    },
                    TreeItem(SkillRowContent("Intelligence", SimpleStringProperty(""), "")).apply {
                        children.setAll(
                            IntelligenceCharacteristic.HpPercentage(0).toTreeItem(),
                            IntelligenceCharacteristic.HpPercentageAsArmor(0).toTreeItem(),
                            IntelligenceCharacteristic.HealReceivedPercentage(0).toTreeItem(),
                            IntelligenceCharacteristic.Shield(0).toTreeItem(),
                            IntelligenceCharacteristic.Resistance(0).toTreeItem()
                        )
                        isExpanded = true
                    },
                    TreeItem(SkillRowContent("Strength", SimpleStringProperty(""), "")).apply {
                        children.setAll(
                            StrengthCharacteristic.Hp(0).toTreeItem(),
                            StrengthCharacteristic.MasteryDistance(0).toTreeItem(),
                            StrengthCharacteristic.MasteryMelee(0).toTreeItem(),
                            StrengthCharacteristic.MasteryElementary(0).toTreeItem()
                        )
                        isExpanded = true
                    },
                    TreeItem(SkillRowContent("Luck", SimpleStringProperty(""), "")).apply {
                        children.setAll(
                            LuckCharacteristic.Block(0).toTreeItem(),
                            LuckCharacteristic.MasteryBack(0).toTreeItem(),
                            LuckCharacteristic.CriticalHit(0).toTreeItem(),
                            LuckCharacteristic.MasteryBerserk(0).toTreeItem(),
                            LuckCharacteristic.MasteryHealing(0).toTreeItem(),
                            LuckCharacteristic.MasteryCritical(0).toTreeItem(),
                            LuckCharacteristic.ResistanceBack(0).toTreeItem(),
                            LuckCharacteristic.ResistanceCritical(0).toTreeItem()
                        )
                        isExpanded = true
                    },
                    TreeItem(SkillRowContent("Major", SimpleStringProperty(""), "")).apply {
                        children.setAll(
                            MajorCharacteristic.ActionPoint(0).toTreeItem(),
                            MajorCharacteristic.MovementPointWithMasteryElementary(0).toTreeItem(),
                            MajorCharacteristic.ControlWithMasteryElementary(0).toTreeItem(),
                            MajorCharacteristic.RangeWithMasteryElementary(0).toTreeItem(),
                            MajorCharacteristic.WakfuPoints(0).toTreeItem(),
                            MajorCharacteristic.DamageInflicted(0).toTreeItem(),
                            MajorCharacteristic.Resistance(0).toTreeItem()
                        )
                        isExpanded = true
                    }
                )
            }
    }

    @Listener
    private fun onBuildUpdate(autobuildUpdateSearchEvent: AutobuildUpdateSearchEvent) {
        autobuildUpdateSearchEvent
            .buildCombination
            .individual
            .characterSkills
            .allCharacteristic
            .forEach { skill ->
                classToSkillCurrentValue[skill::class]?.value = skill.pointsAssigned.toString()
            }
    }

    private fun SkillCharacteristic.toTreeItem(): TreeItem<SkillRowContent> {
        val currentPointAssigned = SimpleStringProperty(pointsAssigned.toString())
        classToSkillCurrentValue[this::class] = currentPointAssigned
        return TreeItem(
            SkillRowContent(
                name = name,
                current = currentPointAssigned,
                max = maxPointsAssignable.toString()
            )
        )
    }
}

data class SkillRowContent(val name: String, val current: ObservableValue<String>, val max: String)
