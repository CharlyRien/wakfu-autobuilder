package me.chosante.bdataextractor

import me.chosante.bdataextractor.FieldType.Bool
import me.chosante.bdataextractor.FieldType.F32
import me.chosante.bdataextractor.FieldType.I16
import me.chosante.bdataextractor.FieldType.I32
import me.chosante.bdataextractor.FieldType.I64
import me.chosante.bdataextractor.FieldType.I8
import me.chosante.bdataextractor.FieldType.MapField
import me.chosante.bdataextractor.FieldType.Str
import me.chosante.bdataextractor.FieldType.Struct
import me.chosante.bdataextractor.FieldType.Vec

/**
 * Positional field schemas for the two Wakfu static-data tables we read. The layout is **untagged**, so
 * every field is listed in exact wire order — the *full* record must be walked for the scramble seed to
 * stay aligned, even though only a handful of fields are used downstream. Order is the entire risk
 * surface; the per-record size guard ([loadTable]) verifies each record consumes exactly its indexed
 * byte length, turning any drift into a hard error.
 *
 * Field order reflects Ankama's `SpellBinaryData` / `StaticEffectBinaryData` serialization (a fact about
 * the binary format); names mirror the public field identifiers. `_n` fields are unnamed/unused padding
 * we still decode for alignment.
 */
object Tables {
    const val SPELL = 66
    const val STATIC_EFFECT = 68

    /** Spell table (TYPE_ID 66). Cast limits, breed, AP, the `passive` flag and `effect_ids` are early. */
    val SPELL_SCHEMA: List<Field> =
        listOf(
            Field("id", I32),
            Field("script_id", I32),
            Field("gfx_id", I32),
            Field("max_level", I16),
            Field("breed_id", I16),
            Field("cast_max_per_target", I16),
            Field("cast_max_per_turn", F32),
            Field("cast_max_per_turn_incr", F32),
            Field("cast_min_interval", I16),
            Field("test_line_of_sight", Bool),
            Field("cast_only_line", Bool),
            Field("cast_only_in_diag", Bool),
            Field("test_free_cell", Bool),
            Field("test_not_border_cell", Bool),
            Field("test_direct_path", Bool),
            Field("_15", Bool),
            Field("target_filter", I32),
            Field("cast_criterion", Str),
            Field("_18", I16),
            Field("pa_base", F32),
            Field("pa_inc", F32),
            Field("pm_base", F32),
            Field("pm_inc", F32),
            Field("pw_base", F32),
            Field("pw_inc", F32),
            Field("range_max_base", F32),
            Field("range_max_inc", F32),
            Field("range_min_base", F32),
            Field("range_min_level_increment", F32),
            Field("max_effect_cap", I16),
            Field("element", I16),
            Field("xp_gain_percentage", I16),
            Field("spell_type", I16),
            Field("ui_position", I16),
            Field("learn_criteria", Str),
            Field("_35", Str),
            Field("passive", I8),
            Field("use_automatic_description", Bool),
            Field("show_in_timeline", Bool),
            Field("can_cast_when_carrying", Bool),
            Field("action_on_critical_miss", I8),
            Field("spell_cast_range_is_dynamic", Bool),
            Field("cast_spell_will_break_invisibility", Bool),
            Field("cast_on_random_cell", Bool),
            Field("tunnelable", Bool),
            Field("can_cast_on_caster_cell", Bool),
            Field("associated_with_item_use", Bool),
            Field("properties", Vec(I32)),
            Field("effect_ids", Vec(I32)),
            Field("base_cast_parameters", MapField(I8, Struct(listOf(Field("base", I32), Field("increment", F32))))),
            Field("_50", MapField(I16, Struct(listOf(Field("_0", I16), Field("_1", Vec(I32)))))),
            Field("_51", MapField(Str, Struct(SPELL_51_FIELDS)))
        )

    private val SPELL_51_FIELDS: List<Field>
        get() =
            buildList {
                add(Field("_0", I32))
                add(Field("_1", MapField(I32, Struct(listOf(Field("_0", I32), Field("_1", F32))))))
                for (i in 2..11) add(Field("_$i", F32))
                for (i in 12..15) add(Field("_$i", Bool))
                for (i in 16..19) add(Field("_$i", F32))
                for (i in 20..23) add(Field("_$i", Bool))
            }

    /** StaticEffect table (TYPE_ID 68): `effect_id`→`action_id`+`params`+`effect_criterion`+duration. */
    val STATIC_EFFECT_SCHEMA: List<Field> =
        listOf(
            Field("effect_id", I32),
            Field("action_id", I32),
            Field("parent_id", I32),
            Field("area_ordering_method", I16),
            Field("area_size", Vec(I32)),
            Field("area_shape", I16),
            Field("empty_cells_area_ordering_method", I16),
            Field("empty_cells_area_size", Vec(I32)),
            Field("empty_cells_area_shape", I16),
            Field("triggers_before_computation", Vec(I32)),
            Field("triggers_before_execution", Vec(I32)),
            Field("triggers_for_unapplication", Vec(I32)),
            Field("triggers_after_execution", Vec(I32)),
            Field("triggers_after_all_executions", Vec(I32)),
            Field("triggers_not_related_to_executions", Vec(I32)),
            Field("triggers_additionnal", Vec(I32)),
            Field("critical_state", Str),
            Field("target_validator", Vec(I64)),
            Field("affected_by_localisation", Bool),
            Field("duration_base", I32),
            Field("duration_inc", F32),
            Field("ends_at_end_of_turn", Bool),
            Field("is_duration_in_full_turns", Bool),
            Field("apply_delay_base", I16),
            Field("apply_delay_increment", F32),
            Field("params", Vec(F32)),
            Field("probability_base", F32),
            Field("probability_inc", F32),
            Field("trigger_listener_type", I8),
            Field("trigger_target_type", I8),
            Field("trigger_caster_type", I8),
            Field("_31", I32),
            Field("store_on_self", Bool),
            Field("max_execution", I16),
            Field("max_execution_incr", F32),
            Field("max_target_count", I8),
            Field("is_fight_effect", Bool),
            Field("hmi_action", Str),
            Field("container_min_level", I16),
            Field("container_max_level", I16),
            Field("effect_criterion", Str),
            Field("effect_parent_type", Str),
            Field("effect_container_type", Str),
            Field("dont_trigger_anything", Bool),
            Field("is_personal", Bool),
            Field("is_decursable", Bool),
            Field("notify_in_chat_for_caster", Bool),
            Field("notify_in_chat_for_target", Bool),
            Field("notify_in_chat_with_caster_name", Bool),
            Field("script_file_id", I32),
            Field("duration_in_caster_turn", Bool),
            Field("effect_properties", Vec(I32)),
            Field("display_in_spell_description", Bool),
            Field("display_in_state_bar", Bool),
            Field("recompute_area_of_effect_display", Bool),
            Field("is_in_turn_in_fight", Bool),
            Field("notify_in_chat", Bool)
        )
}
