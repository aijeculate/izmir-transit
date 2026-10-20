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
            // Debug builds log any disk or network access on the main thread.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        }
        // Marker bitmaps are built before the first map appears; that needs Maps initialised.
        MapsInitializer.initialize(this, MapsInitializer.Renderer.LATEST) {}
        StaticDataRefreshWorker.schedule(this)
    }
}
