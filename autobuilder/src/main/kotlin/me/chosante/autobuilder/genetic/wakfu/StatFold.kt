package me.chosante.autobuilder.genetic.wakfu

import me.chosante.common.Characteristic

/**
 * Fold a `MAX_*` capacity characteristic onto the usable stat it grants: a `MAX_ACTION_POINT` /
 * `MAX_MOVEMENT_POINT` / `MAX_WAKFU_POINTS` sublimation effect feeds `ACTION_POINT` / `MOVEMENT_POINT` /
 * `WAKFU_POINT` respectively; every other characteristic is returned unchanged.
 *
 * This is the SINGLE source of truth for that mapping, shared by the CP-SAT sublimation-term builder, the
 * domination pre-filter, and the scorers (C2 of docs/code-review-followups.md). If Ankama adds a `MAX_*`
 * stat, adding it here keeps all three in lockstep — the previous three independent copies could silently
 * disagree (the domination pre-filter would then pin the wrong stat).
 */
internal fun Characteristic.foldedToUsableStat(): Characteristic =
    when (this) {
        Characteristic.MAX_ACTION_POINT -> Characteristic.ACTION_POINT
        Characteristic.MAX_MOVEMENT_POINT -> Characteristic.MOVEMENT_POINT
        Characteristic.MAX_WAKFU_POINTS -> Characteristic.WAKFU_POINT
        else -> this
    }
