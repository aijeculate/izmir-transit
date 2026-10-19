package com.aijec.izmirtransit.core.data.source.eshot.live

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Wire format of the İztek API. KoorX is LATITUDE and KoorY is LONGITUDE, the reverse
// of the usual x = longitude convention.

@Serializable
internal data class LineBusPositionsResponse(
    @SerialName("HataVarMi") val hasError: Boolean = false,
    @SerialName("HataMesaj") val errorMessage: String? = null,
    // Kept as raw elements so one malformed record is skipped instead of failing the list.
    @SerialName("HatOtobusKonumlari") val positions: List<JsonElement> = emptyList(),
)

@Serializable
internal data class BusPositionDto(
    @SerialName("OtobusId") val busId: Int,
    /** 1 = outbound (dataset YON 1), 0 = return (dataset YON 2). Verified 20 Oct 2026. */
    @SerialName("Yon") val yon: Int,
    @SerialName("KoorX") @Serializable(with = CoordinateSerializer::class) val latitude: Double,
    @SerialName("KoorY") @Serializable(with = CoordinateSerializer::class) val longitude: Double,
)

@Serializable
internal data class ApproachingBusDto(
    @SerialName("OtobusId") val busId: Int,
    @SerialName("HatNumarasi") @Serializable(with = LenientStringSerializer::class) val lineCode: String,
    @SerialName("HatAdi") val lineName: String = "",
    /** Unlike `Yon`, this already uses dataset codes: 1 outbound, 2 return. */
    @SerialName("HattinYonu") val direction: Int,
    @SerialName("KalanDurakSayisi") val stopsRemaining: Int,
    @SerialName("KoorX") @Serializable(with = CoordinateSerializer::class) val latitude: Double,
    @SerialName("KoorY") @Serializable(with = CoordinateSerializer::class) val longitude: Double,
    @SerialName("EngelliMi") val wheelchairAccessible: Boolean = false,
    @SerialName("BisikletAparatliMi") val bikeRack: Boolean = false,
)

/**
 * Coordinates arrive as strings with a comma decimal ("38,45342667") although the docs say
 * double. Accepts both forms; never uses the device locale.
 */
internal object CoordinateSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IztekCoordinate", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Double {
        val text = if (decoder is JsonDecoder) {
            (decoder.decodeJsonElement() as? JsonPrimitive)?.content
        } else {
            decoder.decodeString()
        } ?: throw SerializationException("Coordinate is not a primitive")
        return text.trim().replace(',', '.').toDoubleOrNull()
            ?: throw SerializationException("Unparseable coordinate '$text'")
    }

    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

/** Line numbers arrive as JSON numbers but are text in our model ("M1" later). */
internal object LenientStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String =
        if (decoder is JsonDecoder) {
            (decoder.decodeJsonElement() as? JsonPrimitive)?.content
                ?: throw SerializationException("Expected a primitive")
        } else {
            decoder.decodeString()
        }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
