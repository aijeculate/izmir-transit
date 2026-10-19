package com.aijec.izmirtransit.core.data.source.eshot.live

import com.aijec.izmirtransit.core.data.live.FetchResult
import com.aijec.izmirtransit.core.data.live.RequestPacer
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.LiveError
import com.aijec.izmirtransit.core.model.geo.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException
import java.util.logging.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** A position record as the API sent it, before ghost filtering. */
data class RawBusPosition(val busId: Int, val direction: Direction, val position: LatLng)

data class RawApproachingBus(
    val busId: Int,
    val lineCode: String,
    val lineName: String,
    val direction: Direction,
    val stopsRemaining: Int,
    val position: LatLng,
    val wheelchairAccessible: Boolean,
    val bikeRack: Boolean,
)

/**
 * Talks to the İztek live API. Every call goes through the shared [RequestPacer] because
 * the server rate-limits bursts of ~3 requests per second with HTTP 429.
 */
class IztekLiveDataSource @Inject constructor(
    private val api: IztekApi,
    private val pacer: RequestPacer,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lineBusPositions(lineCode: String): FetchResult<List<RawBusPosition>> =
        call({ api.lineBusPositions(lineCode) }) { element ->
            val response = json.decodeFromJsonElement(LineBusPositionsResponse.serializer(), element)
            if (response.hasError) {
                return@call FetchResult.Failure(LiveError.SERVER, retryable = true, detail = response.errorMessage)
            }
            decodeEach(response.positions, BusPositionDto.serializer()) { dto ->
                val direction = directionFromYon(dto.yon) ?: return@decodeEach null
                RawBusPosition(dto.busId, direction, LatLng(dto.latitude, dto.longitude))
            }
        }

    suspend fun approachingBuses(stopCode: String): FetchResult<List<RawApproachingBus>> =
        call({ api.approachingBuses(stopCode) }, ::decodeApproaching)

    suspend fun lineApproachingBuses(lineCode: String, stopCode: String): FetchResult<List<RawApproachingBus>> =
        call({ api.lineApproachingBuses(lineCode, stopCode) }, ::decodeApproaching)

    private fun decodeApproaching(element: JsonElement): FetchResult<List<RawApproachingBus>> {
        val array = element as? JsonArray
            ?: return FetchResult.Failure(LiveError.MALFORMED, retryable = true, detail = "Expected an array")
        return decodeEach(array, ApproachingBusDto.serializer()) { dto ->
            val direction = Direction.fromDatasetCode(dto.direction) ?: return@decodeEach null
            RawApproachingBus(
                busId = dto.busId,
                lineCode = dto.lineCode,
                lineName = dto.lineName,
                direction = direction,
                stopsRemaining = dto.stopsRemaining,
                position = LatLng(dto.latitude, dto.longitude),
                wheelchairAccessible = dto.wheelchairAccessible,
                bikeRack = dto.bikeRack,
            )
        }
    }

    private suspend fun <T> call(
        request: suspend () -> Response<ResponseBody>,
        decode: (JsonElement) -> FetchResult<List<T>>,
    ): FetchResult<List<T>> {
        val response = try {
            pacer.paced { request() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return FetchResult.Failure(LiveError.NETWORK, retryable = true, detail = e.message)
        }
        return response.use {
            when (val code = response.code()) {
                204 -> FetchResult.Success(emptyList())
                in 200..299 -> {
                    val text = response.body()?.string().orEmpty()
                    if (text.isBlank()) return@use FetchResult.Success(emptyList())
                    try {
                        decode(json.parseToJsonElement(text))
                    } catch (e: SerializationException) {
                        FetchResult.Failure(LiveError.MALFORMED, retryable = true, detail = e.message)
                    } catch (e: IllegalArgumentException) {
                        FetchResult.Failure(LiveError.MALFORMED, retryable = true, detail = e.message)
                    }
                }
                429 -> {
                    pacer.penalize(RATE_LIMIT_PENALTY)
                    FetchResult.Failure(LiveError.RATE_LIMITED, retryable = true, detail = "HTTP 429")
                }
                in 500..599 -> FetchResult.Failure(LiveError.SERVER, retryable = true, detail = "HTTP $code")
                else -> FetchResult.Failure(LiveError.SERVER, retryable = false, detail = "HTTP $code")
            }
        }
    }

    /** Decodes each record on its own; a bad record is logged and skipped. */
    private fun <D, T : Any> decodeEach(
        elements: List<JsonElement>,
        serializer: kotlinx.serialization.KSerializer<D>,
        map: (D) -> T?,
    ): FetchResult<List<T>> {
        var skipped = 0
        val values = elements.mapNotNull { element ->
            val value = try {
                if (element !is JsonObject) null else map(json.decodeFromJsonElement(serializer, element))
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (value == null) {
                skipped++
                log.warning("Skipping malformed İztek record: ${element.toString().take(200)}")
            }
            value
        }
        return FetchResult.Success(values, skippedRecords = skipped)
    }

    private inline fun <R> Response<ResponseBody>.use(block: (Response<ResponseBody>) -> R): R =
        try {
            block(this)
        } finally {
            errorBody()?.close()
            body()?.close()
        }

    companion object {
        private val log = Logger.getLogger(IztekLiveDataSource::class.java.name)
        private val RATE_LIMIT_PENALTY = 5.seconds

        /** Live `Yon` → dataset direction. Verified against route shapes on 20 Oct 2026. */
        fun directionFromYon(yon: Int): Direction? = when (yon) {
            1 -> Direction.OUTBOUND
            0 -> Direction.RETURN
            else -> null
        }
    }
}
