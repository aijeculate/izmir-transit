package com.aijec.izmirtransit.core.data.places

import com.aijec.izmirtransit.core.model.geo.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Google key and the app identity Google checks it against: a key restricted to this
 * Android app only accepts calls that name its package and signing certificate.
 */
data class GoogleApiConfig(val apiKey: String, val packageName: String, val certificateSha1: String) {
    val enabled: Boolean get() = apiKey.isNotBlank()
}

data class PlaceSuggestion(val placeId: String, val title: String, val subtitle: String)

/**
 * Place search for the trip planner, with Places API (New) over HTTPS: autocomplete while
 * typing, then one details call for the chosen place's location. Calls in one search share
 * a session token, which Google bills as a single session. Without a key it returns nothing
 * and the planner searches stops only.
 */
@Singleton
class PlacesRepository @Inject constructor(
    private val client: OkHttpClient,
    private val config: GoogleApiConfig,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var sessionToken = UUID.randomUUID().toString()

    val enabled: Boolean get() = config.enabled

    suspend fun autocomplete(query: String): List<PlaceSuggestion> {
        if (!enabled || query.isBlank()) return emptyList()
        val body = json.encodeToString(
            AutocompleteRequest.serializer(),
            AutocompleteRequest(
                input = query,
                sessionToken = sessionToken,
                // Restricted, not just biased: "Forum" should not offer Forum İstanbul.
                locationRestriction = LocationRestriction(Rectangle(IZMIR_SOUTH_WEST, IZMIR_NORTH_EAST)),
            ),
        )
        val response = call(
            Request.Builder()
                .url("$BASE/places:autocomplete")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build(),
        )
        return json.decodeFromString(AutocompleteResponse.serializer(), response).suggestions.mapNotNull { suggestion ->
            val place = suggestion.placePrediction ?: return@mapNotNull null
            PlaceSuggestion(
                placeId = place.placeId,
                title = place.structuredFormat?.mainText?.text ?: place.text?.text.orEmpty(),
                subtitle = place.structuredFormat?.secondaryText?.text.orEmpty(),
            )
        }
    }

    /** The chosen place's location; ends the billing session. */
    suspend fun location(placeId: String): LatLng {
        val response = call(
            Request.Builder()
                .url("$BASE/places/$placeId?sessionToken=$sessionToken&languageCode=tr")
                .header("X-Goog-FieldMask", "location")
                .get()
                .build(),
        )
        sessionToken = UUID.randomUUID().toString()
        val location = json.decodeFromString(PlaceDetails.serializer(), response).location ?: throw IOException("Place has no location")
        return LatLng(location.latitude, location.longitude)
    }

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        val signed = request.newBuilder()
            .header("X-Goog-Api-Key", config.apiKey)
            .header("X-Android-Package", config.packageName)
            .header("X-Android-Cert", config.certificateSha1)
            .build()
        client.newCall(signed).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Places HTTP ${response.code}: ${text.take(200)}")
            text
        }
    }

    @Serializable
    private data class AutocompleteRequest(
        val input: String,
        val sessionToken: String,
        val locationRestriction: LocationRestriction,
        val languageCode: String = "tr",
        val regionCode: String = "tr",
    )

    @Serializable private data class LocationRestriction(val rectangle: Rectangle)
    @Serializable private data class Rectangle(val low: Point, val high: Point)
    @Serializable private data class Point(val latitude: Double, val longitude: Double)

    @Serializable private data class AutocompleteResponse(val suggestions: List<Suggestion> = emptyList())
    @Serializable private data class Suggestion(val placePrediction: Prediction? = null)
    @Serializable private data class Prediction(
        val placeId: String,
        val text: Text? = null,
        val structuredFormat: Structured? = null,
    )
    @Serializable private data class Structured(val mainText: Text? = null, val secondaryText: Text? = null)
    @Serializable private data class Text(val text: String = "")
    @Serializable private data class PlaceDetails(val location: Point? = null)

    private companion object {
        const val BASE = "https://places.googleapis.com/v1"
        /** İzmir province, Bergama to Selçuk and Çeşme to Kemalpaşa, with a margin. */
        val IZMIR_SOUTH_WEST = Point(37.75, 26.15)
        val IZMIR_NORTH_EAST = Point(39.40, 28.55)
    }
}
