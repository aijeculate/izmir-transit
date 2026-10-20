package com.aijec.izmirtransit

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.aijec.izmirtransit.sync.StaticDataRefreshWorker
import dagger.hilt.android.HiltAndroidApp
import com.google.android.gms.maps.MapsInitializer

@HiltAndroidApp
class IzmirTransitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds log network access on the main thread. Not disk: Google Maps reads
            // disk on every camera move, and StrictMode capturing those stack traces took ~40%
            // of the main thread while panning (simpleperf, 21 Oct 2026).
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyLog().build())
        }
        // Marker bitmaps are built before the first map appears; that needs Maps initialised.
        MapsInitializer.initialize(this, MapsInitializer.Renderer.LATEST) {}
        StaticDataRefreshWorker.schedule(this)
    }
}
