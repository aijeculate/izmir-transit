package com.aijec.izmirtransit.di

import com.aijec.izmirtransit.alerts.AndroidArrivalAlerts
import com.aijec.izmirtransit.core.data.alerts.ArrivalAlerts
import com.aijec.izmirtransit.core.data.trip.TripTracking
import com.aijec.izmirtransit.trip.AndroidTripTracking
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AlertsModule {
    @Binds
    abstract fun arrivalAlerts(impl: AndroidArrivalAlerts): ArrivalAlerts

    @Binds
    abstract fun tripTracking(impl: AndroidTripTracking): TripTracking
}
