package com.aijec.izmirtransit.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LocationResult {
    data class Found(val location: LatLng) : LocationResult
    data object PermissionMissing : LocationResult
    data object LocationOff : LocationResult
    data object Unavailable : LocationResult
}

/** One-shot location for "nearby stops" via the Fused Location Provider. */
@Singleton
class LocationProvider @Inject constructor(@ApplicationContext private val context: Context) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun hasPermission(): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Balanced accuracy (about 100 m, typically from Wi-Fi or cell) is enough for nearby
     * stops and is fast. Falls back to the last known fix if no fresh one arrives in 10 s.
     */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    suspend fun current(): LocationResult {
        if (!hasPermission()) return LocationResult.PermissionMissing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val manager = context.getSystemService(LocationManager::class.java)
            if (manager != null && !manager.isLocationEnabled) return LocationResult.LocationOff
        }
        val cancellation = CancellationTokenSource()
        val fresh = try {
            withTimeoutOrNull(10_000) {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token).await()
            }
        } finally {
            cancellation.cancel()
        }
        val location = fresh ?: runCatching { client.lastLocation.await() }.getOrNull()
        return location?.let { LocationResult.Found(LatLng(it.latitude, it.longitude)) } ?: LocationResult.Unavailable
    }

    /** Precise location, which trip tracking needs to count stops. */
    fun hasPrecisePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A fix with its accuracy in metres. */
    data class Fix(val location: LatLng, val accuracyMeters: Float?)

    /**
     * Continuous high-accuracy fixes every few seconds, for trip tracking. Stops when the
     * collector stops; emits nothing without permission.
     */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun updates(intervalMillis: Long = 5_000): Flow<Fix> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                result.lastLocation?.let { trySend(Fix(LatLng(it.latitude, it.longitude), if (it.hasAccuracy()) it.accuracy else null)) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    companion object {
        /** Approximate is enough; fine is accepted if the user grants it. */
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
