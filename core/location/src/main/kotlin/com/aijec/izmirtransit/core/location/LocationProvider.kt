package com.aijec.izmirtransit.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aijec.izmirtransit.core.model.geo.LatLng
import com.google.android.gms.location.LocationServices
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

    companion object {
        /** Approximate is enough; fine is accepted if the user grants it. */
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
