package me.chosante.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads an [I18nText] that may be stored **either** as a localized object `{fr,en,es,pt}` (the current form)
 * **or** as a legacy plain string. Older saved builds wrote a passive's name/description as a single string;
 * once those fields became [I18nText] those files would no longer load. A legacy string is widened to the
 * same text in all four languages so old history keeps loading; new data always writes the object form.
 *
 * Used for [Passive.name] / [Passive.description] (via `@Serializable(with = …)`), which is why it lives in
 * `common-lib` next to [I18nText]. Falls back to the plain object serializer for non-JSON formats.
 */
object I18nTextOrStringSerializer : KSerializer<I18nText> {
    override val descriptor: SerialDescriptor = I18nText.serializer().descriptor

    override fun deserialize(decoder: Decoder): I18nText {
        val json = decoder as? JsonDecoder ?: return I18nText.serializer().deserialize(decoder)
        val element = json.decodeJsonElement()
        return if (element is JsonPrimitive && element.isString) {
            element.content.let { I18nText(fr = it, en = it, es = it, pt = it) }
        } else {
            json.json.decodeFromJsonElement(I18nText.serializer(), element)
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: I18nText,
    ) {
        I18nText.serializer().serialize(encoder, value)
    }
}
