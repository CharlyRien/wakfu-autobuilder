package me.chosante.ui.components

import androidx.compose.ui.graphics.Color
import me.chosante.common.skills.SkillBranch
import me.chosante.ui.i18n.Tr
import me.chosante.ui.theme.WColor

/** The branch's title in the skill tree and the skill-points card. */
internal fun SkillBranch.labelKey(): Tr =
    when (this) {
        SkillBranch.INTELLIGENCE -> Tr.BRANCH_INTELLIGENCE
        SkillBranch.STRENGTH -> Tr.BRANCH_STRENGTH
        SkillBranch.AGILITY -> Tr.BRANCH_AGILITY
        SkillBranch.LUCK -> Tr.BRANCH_LUCK
        SkillBranch.MAJOR -> Tr.BRANCH_MAJOR
    }

/** The branch's dot colour — the same as the stats column's skill tree. */
internal fun SkillBranch.dotColor(): Color =
    when (this) {
        SkillBranch.INTELLIGENCE -> WColor.earth
        SkillBranch.STRENGTH -> WColor.fire
        SkillBranch.AGILITY -> WColor.accent2
        SkillBranch.LUCK -> Color(0xFFE8C24A)
        SkillBranch.MAJOR -> WColor.accent
    }
