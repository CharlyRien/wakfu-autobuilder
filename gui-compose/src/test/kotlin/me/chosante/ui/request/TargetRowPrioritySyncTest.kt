package me.chosante.ui.request

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.Characteristic
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.state.TargetRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression cover for the priority-meter desync: adding/removing constraint rows used to leave a
 * meter's click handler bound to whatever row originally sat in that slot (positional identity +
 * `pointerInput(Unit)` capturing its callback once), so clicks landed on the wrong row. The fix is a
 * stable `key(target.id)` per row plus `rememberUpdatedState` in the meter. These tests drive the real
 * [TargetRowList] through heavy churn and assert every click stays bound to the *displayed* row.
 */
@OptIn(ExperimentalTestApi::class)
class TargetRowPrioritySyncTest {
    private fun row(
        characteristic: Characteristic,
        weight: Int = 1,
    ): TargetRow =
        TargetRow(
            id = characteristic.name,
            characteristic = characteristic,
            label = characteristic.name,
            glyph = "",
            color = Color.White,
            value = "100",
            weight = weight
        )

    @Test
    fun `priority clicks stay bound to the displayed row across many add and remove cycles`() =
        runComposeUiTest {
            val targets =
                mutableStateListOf(
                    row(Characteristic.ACTION_POINT),
                    row(Characteristic.MOVEMENT_POINT),
                    row(Characteristic.HP)
                )
            // (rowId, weight) reported by the meter, in order — `last()` is the most recent click.
            val recorded = mutableListOf<Pair<String, Int>>()

            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    Column {
                        TargetRowList(
                            mode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT,
                            targets = targets,
                            onValueChange = { _, _ -> },
                            onWeightChange = { id, weight ->
                                recorded += id to weight
                                // Mirror the real model: persist the new weight so the row recomposes.
                                val index = targets.indexOfFirst { it.id == id }
                                if (index >= 0) targets[index] = targets[index].copy(weight = weight)
                            },
                            onRemove = { id -> targets.removeAll { it.id == id } }
                        )
                    }
                }
            }

            // Clicking the centre of a 5-segment bar selects level 3 — see levelForX.
            fun clickAndAssert(characteristic: Characteristic) {
                onNodeWithTag(priorityMeterTestTag(characteristic.name)).performTouchInput { click(center) }
                waitForIdle()
                assertThat(recorded.last()).isEqualTo(characteristic.name to 3)
            }

            // Baseline: each row's bar reports its own id.
            clickAndAssert(Characteristic.ACTION_POINT)
            clickAndAssert(Characteristic.MOVEMENT_POINT)
            clickAndAssert(Characteristic.HP)

            // Remove the MIDDLE row — HP shifts up a slot. This is the case that desynced pre-fix.
            runOnIdle { targets.removeAll { it.id == Characteristic.MOVEMENT_POINT.name } }
            waitForIdle()
            onNodeWithTag(priorityMeterTestTag(Characteristic.MOVEMENT_POINT.name)).assertDoesNotExist()
            clickAndAssert(Characteristic.HP)
            clickAndAssert(Characteristic.ACTION_POINT)

            // Append two fresh rows and click them.
            runOnIdle {
                targets.add(row(Characteristic.RANGE))
                targets.add(row(Characteristic.MASTERY_CRITICAL))
            }
            waitForIdle()
            clickAndAssert(Characteristic.RANGE)
            clickAndAssert(Characteristic.MASTERY_CRITICAL)
            clickAndAssert(Characteristic.HP)

            // Drop the FIRST row and a middle one — now [HP, MASTERY_CRITICAL].
            runOnIdle {
                targets.removeAll { it.id == Characteristic.ACTION_POINT.name }
                targets.removeAll { it.id == Characteristic.RANGE.name }
            }
            waitForIdle()
            clickAndAssert(Characteristic.HP)
            clickAndAssert(Characteristic.MASTERY_CRITICAL)

            // Churn: insert at the FRONT (shifts every existing slot down) then remove, six times over,
            // re-checking both the toggled row and the slot-shifted neighbour each round.
            repeat(6) {
                runOnIdle { targets.add(0, row(Characteristic.WISDOM)) }
                waitForIdle()
                clickAndAssert(Characteristic.WISDOM)
                clickAndAssert(Characteristic.HP)
                runOnIdle { targets.removeAll { it.id == Characteristic.WISDOM.name } }
                waitForIdle()
                onNodeWithTag(priorityMeterTestTag(Characteristic.WISDOM.name)).assertDoesNotExist()
                clickAndAssert(Characteristic.HP)
            }
        }

    @Test
    fun `priority meter maps the click position to a 1 to 5 level`() =
        runComposeUiTest {
            var lastWeight = -1

            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    Column {
                        TargetRowList(
                            mode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT,
                            targets = listOf(row(Characteristic.MASTERY_DISTANCE)),
                            onValueChange = { _, _ -> },
                            onWeightChange = { _, weight -> lastWeight = weight },
                            onRemove = {}
                        )
                    }
                }
            }

            val tag = priorityMeterTestTag(Characteristic.MASTERY_DISTANCE.name)

            onNodeWithTag(tag).performTouchInput { click(Offset(left + 1f, centerY)) }
            waitForIdle()
            assertThat(lastWeight).isEqualTo(1)

            onNodeWithTag(tag).performTouchInput { click(center) }
            waitForIdle()
            assertThat(lastWeight).isEqualTo(3)

            onNodeWithTag(tag).performTouchInput { click(Offset(right - 1f, centerY)) }
            waitForIdle()
            assertThat(lastWeight).isEqualTo(5)
        }
}
