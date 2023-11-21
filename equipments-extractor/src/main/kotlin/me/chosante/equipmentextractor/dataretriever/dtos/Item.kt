package me.chosante.equipmentextractor.dataretriever.dtos

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chosante.common.I18nText

object ItemSerializer : JsonContentPolymorphicSerializer<Item>(Item::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out Item> {
        val itemTypeId =
            element.jsonObject.getValue("definition")
                .jsonObject.getValue("item")
                .jsonObject.getValue("baseParameters")
                .jsonObject.getValue("itemTypeId")
                .jsonPrimitive
                .int
        return when (itemTypeId) {
            812 -> Sublimation.serializer()
            811 -> Enchantment.serializer()
            else -> Equipment.serializer()
        }
    }
}

@Serializable
data class Sublimation(
    override val definition: Definition,
    override val title: I18nText,
    override val description: I18nText? = null,
) : Item()

@Serializable
data class Enchantment(
    override val definition: Definition,
    override val title: I18nText,
    override val description: I18nText? = null,
) : Item()

@Serializable
data class Equipment(
    override val definition: Definition,
    override val title: I18nText,
    override val description: I18nText? = null,
) : Item()

@Serializable
abstract class Item {
    @SerialName("definition")
    abstract val definition: Definition

    @SerialName("title")
    abstract val title: I18nText

    @SerialName("description")
    abstract val description: I18nText?

    @Serializable
    data class Definition(
        @SerialName("item")
        val item: ItemData,
        @SerialName("useEffects")
        val useEffects: List<Effect>,
        @SerialName("useCriticalEffects")
        val useCriticalEffects: List<Effect>,
        @SerialName("equipEffects")
        val equipEffects: List<Effect>,
    ) {
        @Serializable
        data class Effect(
            @SerialName("effect")
            val effect: EffectData,
            @SerialName("subEffects")
            val subEffects: List<Effect>? = null,
        )
    }
}

@Serializable
data class ItemData(
    @SerialName("id")
    val id: Int,
    @SerialName("level")
    val level: Int,
    @SerialName("baseParameters")
    val baseParameters: BaseParameters,
    @SerialName("useParameters")
    val useParameters: UseParameters,
    @SerialName("graphicParameters")
    val graphicParameters: GraphicParameters,
    @SerialName("sublimationParameters")
    val sublimationParameters: SublimationParameters? = null,
    @SerialName("properties")
    val properties: List<Int>,
    @SerialName("shardsParameters")
    val shardsParameters: ShardParameters? = null,
) {
    @Serializable
    data class SublimationParameters(
        @SerialName("slotColorPattern")
        val slotColorPattern: List<Int>,
        @SerialName("isEpic")
        val isEpic: Boolean,
        @SerialName("isRelic")
        val isRelic: Boolean,
    )

    @Serializable
    data class ShardParameters(
        val color: Int,
        val doubleBonusPosition: List<Int>,
        val shardLevelingCurve: List<Int>,
        val shardLevelRequirement: List<Int>,
    )
}

@Serializable
data class BaseParameters(
    @SerialName("itemTypeId")
    val itemTypeId: Int,
    @SerialName("itemSetId")
    val itemSetId: Int,
    @SerialName("rarity")
    val rarity: Int,
    @SerialName("bindType")
    val bindType: Int,
    @SerialName("minimumShardSlotNumber")
    val minimumShardSlotNumber: Int,
    @SerialName("maximumShardSlotNumber")
    val maximumShardSlotNumber: Int,
)

@Serializable
data class UseParameters(
    @SerialName("useCostAp")
    val useCostAp: Int,
    @SerialName("useCostMp")
    val useCostMp: Int,
    @SerialName("useCostWp")
    val useCostWp: Int,
    @SerialName("useRangeMin")
    val useRangeMin: Int,
    @SerialName("useRangeMax")
    val useRangeMax: Int,
    @SerialName("useTestFreeCell")
    val useTestFreeCell: Boolean,
    @SerialName("useTestLos")
    val useTestLos: Boolean,
    @SerialName("useTestOnlyLine")
    val useTestOnlyLine: Boolean,
    @SerialName("useTestNoBorderCell")
    val useTestNoBorderCell: Boolean,
    @SerialName("useWorldTarget")
    val useWorldTarget: Int,
)

@Serializable
data class GraphicParameters(
    @SerialName("gfxId")
    val gfxId: Int,
    @SerialName("femaleGfxId")
    val femaleGfxId: Int,
)

@Serializable
data class EffectData(
    @SerialName("definition")
    val definition: EffectDefinition,
    @SerialName("description")
    val description: I18nText? = null,
) {
    @Serializable
    data class EffectDefinition(
        @SerialName("id")
        val id: Int,
        @SerialName("actionId")
        val actionId: Int,
        @SerialName("areaShape")
        val areaShape: Int,
        @SerialName("areaSize")
        val areaSize: List<Int>,
        @SerialName("params")
        val params: List<Double>,
    )
}
